package com.opsdesk.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.auth.dto.LoginRequest;
import com.opsdesk.auth.service.AuthService;
import com.opsdesk.auth.vo.LoginUserVO;
import com.opsdesk.auth.vo.LoginVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentService;
import com.opsdesk.user.entity.Permission;
import com.opsdesk.user.entity.Role;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.PermissionService;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 认证 Service 实现（工单 D1-01）
 *
 * <p>规格依据：规格基线 §4.1 / §23.1（BCrypt strength 10、5 次失败锁 10 分钟）/ §23.2（JWT 载荷）、
 * API 文档 §4.1（字段与错误码）/ §2.4（错误码）/ §16.3（LoginUserVO）。
 *
 * <p><b>本工单的两条硬约束</b>：
 * <ol>
 *   <li><b>权限不进 JWT</b>（§23.2）—— {@code JwtHelper#createToken} 只放 sub / jti / iat / exp，
 *       权限由本方法查库返回给前端；请求期的权限校验走 Redis {@code auth:perms:{userId}}（D2-01）</li>
 *   <li><b>不跳过失败锁定</b>—— 锁定校验放在验密<b>之前</b>，
 *       所以第 6 次即使密码正确也会被拒（工单验收 2）</li>
 * </ol>
 *
 * <p>登录失败一律 {@code 40100}（不区分「用户不存在」与「密码错误」，避免账号枚举）；
 * 处于锁定窗口内才返回 {@code 42900}（API 文档 §4.1 错误表）。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    // ==================== 常量（规格基线 §23.1 / §23.4） ====================

    /** BCrypt strength（§23.1 固定 10） */
    private static final int BCRYPT_STRENGTH = 10;

    /** 同一账号连续失败上限，达到即锁定（§23.1） */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** 失败计数 TTL = 锁定时长（§23.1 / §23.4：10 分钟） */
    private static final long LOGIN_FAIL_TTL_MINUTES = 10L;

    /** 登录失败计数 Redis key 前缀（§23.4：{@code login:fail:{username}}） */
    private static final String LOGIN_FAIL_KEY_PREFIX = "login:fail:";

    /** {@code user.status} 启用值（DDL 注释：1 启用 0 禁用） */
    private static final int USER_STATUS_ENABLED = 1;

    /** 对外统一文案：不区分「用户不存在」与「密码错误」 */
    private static final String MSG_BAD_CREDENTIALS = "用户名或密码错误";

    // ==================== 依赖 ====================

    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final RolePermissionService rolePermissionService;
    private final PermissionService permissionService;
    private final DepartmentService departmentService;
    private final StringRedisTemplate redisTemplate;
    private final JwtHelper jwtHelper;

    /**
     * 密码编码器。这里直接 {@code new} 而不是做成 {@code @Bean}：
     * 本工单「允许改动」只含 {@code com.opsdesk.auth} 下的 controller / service / dto / vo，
     * 不新增 config 包；D1-03（用户 CRUD）需要给新用户加密时再抽成共享 Bean。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public AuthServiceImpl(UserService userService,
                           UserRoleService userRoleService,
                           RoleService roleService,
                           RolePermissionService rolePermissionService,
                           PermissionService permissionService,
                           DepartmentService departmentService,
                           StringRedisTemplate redisTemplate,
                           JwtHelper jwtHelper) {
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.rolePermissionService = rolePermissionService;
        this.permissionService = permissionService;
        this.departmentService = departmentService;
        this.redisTemplate = redisTemplate;
        this.jwtHelper = jwtHelper;
    }

    // ==================== 主流程 ====================

    @Override
    public LoginVO login(LoginRequest request) {
        // 用户名前后空格归一化：既提升体验，也避免 "admin " 与 "admin" 变成两个失败计数 key
        String username = request.getUsername().trim();

        // 1) 锁定校验 —— 必须在验密之前，否则「第 6 次用正确密码」会被放过
        assertNotLocked(username);

        // 2) 查用户。@TableLogic 自动追加 deleted = 0，已删除账号查不到
        //
        // 只按 username 查，**绝不能把密码放进 WHERE**：
        //    BCrypt 是「加盐 + 每次结果都不同」
        User user = userService.getOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        // 3) 验密。用户不存在与密码错误合并处理，避免暴露账号是否存在
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            int failures = recordLoginFailure(username);
            log.warn("[登录失败] username={} 原因=用户名或密码错误 累计失败={} 次", username, failures);
            throw new BizException(ErrorCode.UNAUTHORIZED, MSG_BAD_CREDENTIALS);
        }

        // 4) 禁用账号拒绝登录（§23.1；DDL：status 1 启用 0 禁用）
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            log.warn("[登录失败] userId={} username={} 原因=账号已禁用", user.getId(), username);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已被禁用");
        }

        // 5) 登录成功 → 清掉失败计数（§23.1）
        clearLoginFailures(username);

        // 6) 组装用户信息（角色 / 权限 / 部门）
        LoginUserVO userVO = buildLoginUserVO(user);

        // 7) 签发 JWT。§23.2：载荷只有 sub / jti / iat / exp，**不放权限列表**
        String token = jwtHelper.createToken(user.getId());

        log.info("[登录成功] userId={} username={} roles={} 权限数={}",
                user.getId(), username, userVO.getRoles(), userVO.getPermissions().size());
        return new LoginVO(token, userVO);
    }

    // ==================== 失败锁定（Redis login:fail:{username}） ====================

    /**
     * 校验账号是否处于失败锁定中；是则抛 {@code 42900}。
     *
     * <p>锁定判定：计数 ≥ {@value #MAX_LOGIN_FAILURES}。计数的 TTL 就是锁定时长 ——
     * {@link #recordLoginFailure} 每次失败都刷新 TTL，因此锁定从「最后一次失败」起算 10 分钟，
     * key 过期即自动解锁（§23.1 / §23.4）。
     */
    private void assertNotLocked(String username) {
        String key = loginFailKey(username);
        String value = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            return;
        }
        long failures;
        try {
            failures = Long.parseLong(value.trim());
        }
        catch (NumberFormatException ex) {
            // 脏数据不该阻断登录，当未锁定处理，交给后续验密
            log.warn("[登录锁定] 计数不是数字，已忽略。key={} value={}", key, value);
            return;
        }
        if (failures < MAX_LOGIN_FAILURES) {
            return;
        }
        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long remainMinutes = (ttlSeconds == null || ttlSeconds <= 0)
                ? LOGIN_FAIL_TTL_MINUTES
                : (ttlSeconds + 59) / 60;
        log.warn("[登录锁定] username={} 累计失败={} 次，剩余锁定 {} 分钟", username, failures, remainMinutes);
        throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                String.format("账号已锁定，请 %d 分钟后重试", remainMinutes));
    }

    /**
     * 记录一次登录失败并返回累计次数。
     *
     * <p>INCR + EXPIRE 刷新 TTL：计数窗口随每次失败向后滑动，
     * 因此「连续 5 次」不会因为拖了很久而永不到达阈值。
     */
    private int recordLoginFailure(String username) {
        String key = loginFailKey(username);
        Long failures = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, LOGIN_FAIL_TTL_MINUTES, TimeUnit.MINUTES);
        return failures == null ? 1 : failures.intValue();
    }

    /** 登录成功清除失败计数 */
    private void clearLoginFailures(String username) {
        redisTemplate.delete(loginFailKey(username));
    }

    private String loginFailKey(String username) {
        return LOGIN_FAIL_KEY_PREFIX + username;
    }

    // ==================== 用户信息组装 ====================

    /**
     * 组装 {@link LoginUserVO}：用户基本信息 + 部门名 + 角色码 + 权限码。
     *
     * <p>本工单直接查库（4 次批量查询，无循环内查库）：
     * {@code user_role} → {@code role} → {@code role_permission} → {@code permission}。
     * Redis 缓存 {@code auth:perms:{userId}} 放 D2-01。
     */
    private LoginUserVO buildLoginUserVO(User user) {
        List<Long> roleIds = loadRoleIds(user.getId());
        List<Role> roles = roleIds.isEmpty() ? List.of() : roleService.listByIds(roleIds);

        LoginUserVO vo = new LoginUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setDepartmentName(loadDepartmentName(user.getDepartmentId()));
        vo.setStatus(user.getStatus());
        vo.setRoles(roles.stream()
                .filter(r -> r.getCode() != null)
                .sorted(Comparator.comparing(Role::getId))
                .map(r -> r.getCode().name())
                .distinct()
                .toList());
        vo.setPermissions(loadPermissionCodes(roleIds));
        return vo;
    }

    /** 用户 → 角色 ID 列表 */
    private List<Long> loadRoleIds(Long userId) {
        return userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .select(UserRole::getRoleId)
                        .eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 角色 ID 列表 → 权限码列表（UserRole → Role → RolePermission → Permission 的后两跳）。
     *
     * <p>按 {@code permission.sort} 再按 id 排序：seed 里 MENU 在前、API 在后，
     * 前端拿到的顺序与权限树一致，且结果稳定可断言。
     */
    private List<String> loadPermissionCodes(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = rolePermissionService.list(new LambdaQueryWrapper<RolePermission>()
                        .select(RolePermission::getPermissionId)
                        .in(RolePermission::getRoleId, roleIds))
                .stream()
                .map(RolePermission::getPermissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionService.listByIds(permissionIds).stream()
                .filter(p -> StringUtils.hasText(p.getCode()))
                .sorted(Comparator
                        .comparing((Permission p) -> p.getSort() == null ? 0 : p.getSort())
                        .thenComparing(Permission::getId))
                .map(Permission::getCode)
                .distinct()
                .toList();
    }

    /** 部门名；未设部门或部门已删除时返回 null */
    private String loadDepartmentName(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentService.getById(departmentId);
        return department == null ? null : department.getName();
    }
}
