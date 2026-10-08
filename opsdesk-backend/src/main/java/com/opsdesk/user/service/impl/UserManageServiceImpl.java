package com.opsdesk.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentService;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.user.dto.UserCreateDTO;
import com.opsdesk.user.dto.UserQuery;
import com.opsdesk.user.dto.UserRoleDTO;
import com.opsdesk.user.dto.UserStatusDTO;
import com.opsdesk.user.dto.UserUpdateDTO;
import com.opsdesk.user.entity.Role;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserManageService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import com.opsdesk.user.vo.RoleVO;
import com.opsdesk.user.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理业务实现（工单 D1-03）
 *
 * <p>规格依据：API 文档 §5.1–§5.7、规格基线 §21.2 / §23.2 / §23.4 / §3.5。
 *
 * <h2>本类的三个关键点</h2>
 *
 * <h3>① 逻辑删除 + {@code uk_user_username} 的坑（最容易漏）</h3>
 * {@code @TableLogic} 让所有查询自动带 {@code deleted = 0}，所以「查重」<b>查不到</b>
 * 已被逻辑删除的同名用户；但 {@code uk_user_username} 是普通唯一索引、
 * <b>不排除已删记录</b>（API 文档 §5.7 自己明确写了「用户名被软删后仍不可复用」）。
 * <p>只靠查重会在 INSERT 时撞唯一索引 → 抛 {@code DuplicateKeyException} →
 * 被 {@code GlobalExceptionHandler} 兜底成 <b>50000</b>，而不是规格要求的 <b>40900</b>。
 * <p>所以这里两层防护：先 {@link #assertUsernameAvailable} 走快速路径（文案友好），
 * 再在写入处 {@code catch (DuplicateKeyException)} 转 40900（兜住「已删同名」）。
 * <b>不写「绕过 @TableLogic」的自定义 SQL</b> —— {@code user/mapper/} 不在本工单允许改动内。
 *
 * <h3>② 批量装配 VO，不做 N+1</h3>
 * 列表页的部门名与角色是<b>整页批量查</b>（部门 1 条 + user_role 1 条 + role 1 条 = 3 条），
 * 不是每行一次。
 *
 * <h3>③ 禁用用户仍可持旧 token —— 本类只清缓存，真正修复留给 D2-01</h3>
 * API 文档 §5.5 的注释说「禁用后该用户 token 仍有效，但权限缓存会在角色/状态变更时清除，
 * 下次请求即被拦截」。<b>后半句不成立</b>：状态变更不改变角色与权限，清掉缓存后回源查库
 * 拿到的权限<b>一模一样</b>，D2-02 的 {@code @RequirePermission} 照样放行。
 * <p>本类按 §5.5 的<b>动作</b>实现（清缓存，且此刻 {@code PermissionLoader} 还没落地，
 * 删的是一个目前不存在的 key，属提前把 key 契约写对）；
 * 真正的修复建议放进 D2-01：把 {@code user.status} 一并放进 {@code auth:perms:{userId}}
 * 的缓存载荷，拦截器零额外查询即可拒绝禁用用户。
 */
@Slf4j
@Service
public class UserManageServiceImpl implements UserManageService {

    /** BCrypt strength（规格基线 §23.1 固定 10） */
    private static final int BCRYPT_STRENGTH = 10;

    /** 密码长度下界（§5.3 / §5.4：6–64） */
    private static final int PASSWORD_MIN_LENGTH = 6;

    /** {@code user.status} 启用值（DDL 注释：1 启用 0 禁用） */
    private static final int USER_STATUS_ENABLED = 1;

    /** 权限缓存 key 前缀（规格基线 §23.4：{@code auth:perms:{userId}}） */
    private static final String PERMS_CACHE_PREFIX = "auth:perms:";

    /** 用户名冲突的统一文案（§5.3 / §5.4 均映射 40900） */
    private static final String MSG_USERNAME_EXISTS = "用户名已存在";

    /**
     * 「未关闭」= 非终态，直接从 {@code TicketStatus} 的 {@code terminal} 标志推导，
     * <b>不硬编码 CLOSED / CANCELLED</b> —— §3.5 才是「是否终态」的 SSOT，
     * 将来加终态枚举时这里自动跟随。
     *
     * <p>⚠️ 用 {@code in} 而不是 {@code notIn}：这两个很容易写反。
     * {@code notIn(非终态集合)} 等于「只统计终态工单」，语义正好相反 ——
     * 结果是「有未关闭工单」永远查不出来，删除校验形同虚设。
     */
    private static final List<TicketStatus> NON_TERMINAL_STATUSES =
            Arrays.stream(TicketStatus.values()).filter(s -> !s.isTerminal()).toList();

    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final DepartmentService departmentService;
    private final TicketService ticketService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 密码编码器。这里第三次 {@code new} 同一个东西（D1-01 的 {@code AuthServiceImpl}
     * 也各有一个）：本工单「允许改动」只含 {@code user/controller|service|dto|vo}，
     * 加不了 config 包，所以没抽共享 Bean。
     * <b>建议后续单独一张工单抽 {@code PasswordEncoder} Bean，把 strength 收敛到一处。</b>
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public UserManageServiceImpl(UserService userService,
                                 UserRoleService userRoleService,
                                 RoleService roleService,
                                 DepartmentService departmentService,
                                 TicketService ticketService,
                                 StringRedisTemplate redisTemplate) {
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.departmentService = departmentService;
        this.ticketService = ticketService;
        this.redisTemplate = redisTemplate;
    }

    // ==================== §5.1 列表 ====================

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        long pageNo = query.pageOrDefault();
        long size = query.sizeOrDefault();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        // 只取需要的列：password 连内存都不进（多一层保险）
        wrapper.select(User::getId, User::getUsername, User::getNickname, User::getEmail,
                User::getDepartmentId, User::getStatus, User::getCreatedAt);

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            String kw = escapeLikeWildcards(keyword);
            wrapper.and(w -> w.like(User::getUsername, kw).or().like(User::getNickname, kw));
        }

        if (query.getDepartmentId() != null) {
            List<Long> departmentIds = resolveDepartmentSubtreeIds(query.getDepartmentId());
            if (departmentIds.isEmpty()) {
                // 部门不存在 → 空结果（筛选条件命中不了任何用户，不是参数错误）
                return PageResult.empty(pageNo, size);
            }
            wrapper.in(User::getDepartmentId, departmentIds);
        }

        if (query.getStatus() != null) {
            wrapper.eq(User::getStatus, query.getStatus());
        }

        // 未指定排序时的稳定默认：新用户在前
        wrapper.orderByDesc(User::getId);

        Page<User> page = userService.page(new Page<>(pageNo, size), wrapper);
        return PageResult.of(toUserVOList(page.getRecords()), page.getTotal(), pageNo, size);
    }

    // ==================== §5.2 详情 ====================

    @Override
    public UserVO detail(Long id) {
        // 复用列表的批量装配，保证「详情」与「列表里的同一行」字段口径完全一致
        return toUserVOList(List.of(requireUser(id))).get(0);
    }

    // ==================== §5.3 创建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(UserCreateDTO dto) {
        String username = dto.getUsername().trim();
        assertUsernameAvailable(username, null);
        assertDepartmentExists(dto.getDepartmentId());
        List<Long> roleIds = normalizeRoleIds(dto.getRoleIds());

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
        user.setEmail(trimToNull(dto.getEmail()));
        user.setDepartmentId(dto.getDepartmentId());
        user.setStatus(USER_STATUS_ENABLED);
        // deleted / created_at / updated_at 交给 DB 默认值（实体上已配 insertStrategy = NEVER）

        try {
            userService.save(user);
        }
        catch (DuplicateKeyException ex) {
            // 查重查不到、但唯一索引挡住了 —— 只可能是「已被逻辑删除的同名用户」（§5.7）
            log.warn("[创建用户] 撞唯一索引 uk_user_username，判定为用户名冲突。username={}", username);
            throw new BizException(ErrorCode.CONFLICT, MSG_USERNAME_EXISTS);
        }

        saveUserRoles(user.getId(), roleIds);
        log.info("[创建用户] id={} username={} departmentId={} roleIds={}",
                user.getId(), username, dto.getDepartmentId(), roleIds);
        return user.getId();
    }

    // ==================== §5.4 修改 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserUpdateDTO dto) {
        User existing = requireUser(id);

        String username = dto.getUsername().trim();
        if (!username.equals(existing.getUsername())) {
            // 改了用户名才查重，且要排除自己
            assertUsernameAvailable(username, id);
        }
        assertDepartmentExists(dto.getDepartmentId());
        List<Long> roleIds = normalizeRoleIds(dto.getRoleIds());

        User update = new User();
        update.setId(id);
        update.setUsername(username);
        update.setNickname(dto.getNickname().trim());
        update.setEmail(trimToNull(dto.getEmail()));
        update.setDepartmentId(dto.getDepartmentId());

        // password 为空 = 不修改（§5.4）。MyBatis-Plus 默认 NOT_NULL 更新策略，
        // 字段为 null 就不会出现在 SET 里，正好符合语义
        if (StringUtils.hasText(dto.getPassword())) {
            if (dto.getPassword().length() < PASSWORD_MIN_LENGTH) {
                // 长度下界放这里而不是 DTO 注解：@Size(min=6) 会把「空串=不修改」也判成非法
                throw new BizException(ErrorCode.PARAM_INVALID, "密码长度需在 6-64 之间");
            }
            update.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        try {
            userService.updateById(update);
        }
        catch (DuplicateKeyException ex) {
            log.warn("[修改用户] 撞唯一索引 uk_user_username。id={} username={}", id, username);
            throw new BizException(ErrorCode.CONFLICT, MSG_USERNAME_EXISTS);
        }

        saveUserRoles(id, roleIds);
        // 角色 / 部门可能变了 → 清权限缓存。即使两者都没变，多清一次也只是让下次请求回源查库，无害
        evictPermissionCache(id);
        log.info("[修改用户] id={} username={} roleIds={} 密码是否修改={}",
                id, username, roleIds, StringUtils.hasText(dto.getPassword()));
    }

    // ==================== §5.5 修改状态 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, UserStatusDTO dto) {
        requireUser(id);

        User update = new User();
        update.setId(id);
        update.setStatus(dto.getStatus());
        userService.updateById(update);

        // §5.5 要求清权限缓存。⚠️ 仅清缓存拦不住已禁用用户的存量 token，见类注释 ③
        evictPermissionCache(id);
        log.info("[修改用户状态] id={} status={}", id, dto.getStatus());
    }

    // ==================== §5.6 分配角色 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long id, UserRoleDTO dto) {
        requireUser(id);
        List<Long> roleIds = normalizeRoleIds(dto.getRoleIds());

        saveUserRoles(id, roleIds);
        // §23.2：删掉权限缓存 → 角色变更立即生效，用户无需重新登录
        evictPermissionCache(id);
        log.info("[分配角色] id={} roleIds={}", id, roleIds);
    }

    // ==================== §5.7 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireUser(id);

        long openTickets = countOpenTickets(id);
        if (openTickets > 0) {
            log.warn("[删除用户] 被拒：存在未关闭工单。id={} 未关闭工单数={}", id, openTickets);
            throw new BizException(ErrorCode.CONFLICT,
                    "该用户存在 " + openTickets + " 张未关闭的工单，禁止删除");
        }

        // 逻辑删除：@TableLogic 会把它改写成 UPDATE user SET deleted = 1 WHERE id = ? AND deleted = 0
        userService.removeById(id);
        evictPermissionCache(id);
        log.info("[删除用户] id={} 逻辑删除完成（deleted = 1，物理行保留）", id);
    }

    /**
     * 统计该用户创建或受理的未关闭工单（§21.2 / §5.7）。
     *
     * <pre>
     * SELECT COUNT(*) FROM ticket
     * WHERE deleted = 0 AND status IN (6 个非终态) AND (creator_id = ? OR assignee_id = ?)
     * </pre>
     * <p>索引：{@code idx_ticket_creator(creator_id, created_at)} 覆盖 creator_id 分支；
     * assignee_id 分支靠 {@code idx_ticket_scope(deleted, status, assignee_id, created_at)}。
     * <p>⚠️ {@code OR} 跨两列时 MySQL 可能退化成全表扫描（也可能走 index_merge）。
     * 当前数据量下可接受；若工单量上来了，把这条拆成两次 COUNT 更稳。
     */
    private long countOpenTickets(Long userId) {
        return ticketService.count(new LambdaQueryWrapper<Ticket>()
                .in(Ticket::getStatus, NON_TERMINAL_STATUSES)
                .and(w -> w.eq(Ticket::getCreatorId, userId).or().eq(Ticket::getAssigneeId, userId)));
    }

    // ==================== 私有工具 ====================

    /** 取用户，不存在抛 40400 */
    private User requireUser(Long id) {
        User user = userService.getById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    /**
     * 用户名可用性检查（快速路径）。
     *
     * <p>⚠️ 只能覆盖「未删除」的同名用户 —— {@code @TableLogic} 会追加 {@code deleted = 0}。
     * 「已删除同名」这条路径由写入处的 {@code catch (DuplicateKeyException)} 兜住。
     *
     * @param excludeId 修改场景传自己的 id，把自己排除掉
     */
    private void assertUsernameAvailable(String username, Long excludeId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .select(User::getId)
                .eq(User::getUsername, username);
        if (excludeId != null) {
            wrapper.ne(User::getId, excludeId);
        }
        if (userService.count(wrapper) > 0) {
            throw new BizException(ErrorCode.CONFLICT, MSG_USERNAME_EXISTS);
        }
    }

    /** 部门必须存在且未删除（§5.3） */
    private void assertDepartmentExists(Long departmentId) {
        if (departmentService.getById(departmentId) == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属部门不存在或已删除");
        }
    }

    /** 角色 ID 去重 + 存在性校验，返回归一化后的列表 */
    private List<Long> normalizeRoleIds(List<Long> roleIds) {
        List<Long> distinct = roleIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少需要分配一个角色");
        }
        if (roleService.listByIds(distinct).size() != distinct.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "存在无效的角色 ID");
        }
        return distinct;
    }

    /** 全量覆盖用户角色：先删旧关联，再批量插新的 */
    private void saveUserRoles(Long userId, List<Long> roleIds) {
        userRoleService.remove(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        List<UserRole> links = roleIds.stream().map(roleId -> {
            UserRole link = new UserRole();
            link.setUserId(userId);
            link.setRoleId(roleId);
            return link;
        }).toList();
        userRoleService.saveBatch(links);
    }

    /**
     * 清除用户的权限缓存（§23.2 / §23.4）。
     *
     * <p>⚠️ {@code PermissionLoader} 是 D2-01 的交付物，本工单还没有缓存写入方，
     * 所以这里删的是一个目前不存在的 key（Redis 删不存在的 key 是 no-op）。
     * 属「提前把 key 契约写对」；D2-01 落地后本方法立即生效。
     */
    private void evictPermissionCache(Long userId) {
        redisTemplate.delete(PERMS_CACHE_PREFIX + userId);
    }

    /**
     * 把部门筛选条件展开成「该部门 + 其所有子孙部门」的 ID 列表（§5.1 的「含子部门」）。
     *
     * <pre>SELECT id FROM department WHERE deleted = 0 AND path LIKE '/1/2/%'</pre>
     * <p>{@code department.path} 是「含自身」的祖先路径（如 {@code /1/2/3/}），
     * 所以对目标部门的 path 做<b>常量前缀 LIKE</b> 就正好覆盖自己 + 整棵子树，
     * 且命中索引 {@code idx_dept_path}。
     * <p>前缀匹配天然不会误伤兄弟节点：{@code /1/3/%} 匹配不到 {@code /1/30/}。
     */
    private List<Long> resolveDepartmentSubtreeIds(Long departmentId) {
        Department department = departmentService.getById(departmentId);
        if (department == null) {
            return List.of();
        }
        String path = department.getPath();
        if (!StringUtils.hasText(path)) {
            // path 异常时退化成「只筛本部门」，而不是把整表放出去
            return List.of(department.getId());
        }
        return departmentService.list(new LambdaQueryWrapper<Department>()
                        .select(Department::getId)
                        .likeRight(Department::getPath, path))
                .stream()
                .map(Department::getId)
                .toList();
    }

    /**
     * 批量装配 {@link UserVO}。
     *
     * <p>固定 3 条批量查询（部门 / user_role / role），与用户数无关 —— 无 N+1。
     */
    private List<UserVO> toUserVOList(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }

        List<Long> departmentIds = users.stream()
                .map(User::getDepartmentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> departmentNames = departmentIds.isEmpty() ? Map.of()
                : departmentService.listByIds(departmentIds).stream()
                        .collect(Collectors.toMap(Department::getId, Department::getName));

        List<UserRole> links = userRoleService.list(new LambdaQueryWrapper<UserRole>()
                .in(UserRole::getUserId, users.stream().map(User::getId).toList()));
        Map<Long, List<Long>> roleIdsByUser = links.stream().collect(Collectors.groupingBy(
                UserRole::getUserId, Collectors.mapping(UserRole::getRoleId, Collectors.toList())));
        Set<Long> allRoleIds = links.stream().map(UserRole::getRoleId).collect(Collectors.toSet());
        Map<Long, Role> rolesById = allRoleIds.isEmpty() ? Map.of()
                : roleService.listByIds(allRoleIds).stream()
                        .collect(Collectors.toMap(Role::getId, role -> role));

        return users.stream().map(user -> {
            UserVO vo = new UserVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            vo.setEmail(user.getEmail());
            vo.setDepartmentId(user.getDepartmentId());
            vo.setDepartmentName(user.getDepartmentId() == null
                    ? null : departmentNames.get(user.getDepartmentId()));
            vo.setStatus(user.getStatus());
            vo.setCreatedAt(user.getCreatedAt());
            vo.setRoles(roleIdsByUser.getOrDefault(user.getId(), List.of()).stream()
                    .map(rolesById::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Role::getId))
                    .map(this::toRoleVO)
                    .toList());
            return vo;
        }).toList();
    }

    private RoleVO toRoleVO(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode() == null ? null : role.getCode().name());
        vo.setDescription(role.getDescription());
        return vo;
    }

    /** 去首尾空白；全空白视为 null */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 转义 LIKE 通配符。
     *
     * <p>参数本身是预编译的（没有 SQL 注入），但用户输入的 {@code %} / {@code _}
     * 会被 MySQL 当成通配符，让 {@code ?keyword=%} 变成「匹配所有用户」。
     * MySQL 默认转义符是 {@code \}，所以先补一层转义。
     */
    private static String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
