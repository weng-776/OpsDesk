package com.opsdesk.organization.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.organization.dto.DepartmentCreateDTO;
import com.opsdesk.organization.dto.DepartmentUpdateDTO;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentManageService;
import com.opsdesk.organization.service.DepartmentService;
import com.opsdesk.organization.vo.DepartmentNodeVO;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 部门管理业务实现（工单 D1-04）
 *
 * <p>规格依据：API 文档 §6.1–§6.4、规格基线 §8.3、`OpsDesk_DDL_V1.sql` 的 {@code department.path} 注释。
 *
 * <h2>{@code path} 规则（本类的全部复杂度都在这）</h2>
 * <pre>
 * 根部门：path = "/" + id + "/"            例：/1/
 * 子部门：path = 父.path + id + "/"         例：/1/2/、/1/2/3/
 * </pre>
 * {@code path} 是「含自身」的祖先路径，所以对某个部门的 {@code path} 做<b>常量前缀 LIKE</b>
 * 就正好覆盖「自己 + 整棵子树」—— 这是规格基线 §8.3 里
 * {@code creator_dept_path LIKE CONCAT(#{myDeptPath}, '%')} 能实现「含子部门递归」的原因。
 *
 * <h2>为什么移动子树是「1 条 SELECT + 批量 UPDATE」而不是一条 SQL</h2>
 * 「一条 SQL 重写整棵子树」要写成
 * {@code UPDATE department SET path = CONCAT(?, SUBSTRING(path, LENGTH(?)+1)) WHERE path LIKE ?}，
 * 而自定义 SQL 得挂在 {@code DepartmentMapper} 上 —— 它是<b>生成物</b>，
 * 下次重跑 {@code gen_entities.py} 就静默丢失（和 D1-03 的 {@code UserService} 同一个坑），
 * 且 {@code organization/mapper/} 不在本工单允许改动内。
 * 部门表数据量极小（种子 6 行），批量 UPDATE 完全够用。
 *
 * <h2>事务边界</h2>
 * {@link #create} 与 {@link #update} 都必须 {@code @Transactional}：
 * 前者是「INSERT 拿 id → 回填 path」两步，后者是「改自身 → 级联改子孙」两步，
 * 任何一步失败都必须整体回滚，否则会留下 path 与父子关系不一致的脏数据
 * —— 而 {@code path} 一旦不一致，§8.3 的数据范围会<b>静默漏数据</b>（不报错，只是看不见）。
 */
@Slf4j
@Service
public class DepartmentManageServiceImpl implements DepartmentManageService {

    /** 根部门的父 ID（§6.2：{@code 0} 表示根） */
    private static final long ROOT_PARENT_ID = 0L;

    /** path 分隔符（DDL：祖先路径含自身，如 {@code /1/3/}） */
    private static final String PATH_SEPARATOR = "/";

    /** 新建部门时的 path 占位值（DDL 里该列 NOT NULL DEFAULT '/'） */
    private static final String PATH_PLACEHOLDER = "/";

    /** {@code department.status} 启用值（DDL 注释：1 启用 0 禁用） */
    private static final int STATUS_ENABLED = 1;

    /** {@code sort} 缺省值（§6.2：非必填，默认 0） */
    private static final int DEFAULT_SORT = 0;

    private final DepartmentService departmentService;
    private final UserService userService;

    public DepartmentManageServiceImpl(DepartmentService departmentService, UserService userService) {
        this.departmentService = departmentService;
        this.userService = userService;
    }

    // ==================== §6.1 部门树 ====================

    @Override
    public List<DepartmentNodeVO> tree() {
        // 一次查全量排序在内存建树时也保持一致
        List<Department> all = departmentService.list(new LambdaQueryWrapper<Department>()
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId));

        Map<Long, DepartmentNodeVO> nodes = new LinkedHashMap<>();
        for (Department department : all) {
            DepartmentNodeVO node = new DepartmentNodeVO();
            node.setId(department.getId());
            node.setParentId(department.getParentId());
            node.setName(department.getName());
            node.setSort(department.getSort());
            node.setStatus(department.getStatus());
            nodes.put(department.getId(), node);
        }

        List<DepartmentNodeVO> roots = new ArrayList<>();
        for (Department department : all) {
            DepartmentNodeVO node = nodes.get(department.getId());
            DepartmentNodeVO parent = department.getParentId() == null
                    ? null : nodes.get(department.getParentId());

            if (parent == null) {
                // 两种情况都归为根：parentId = 0（真根），
                // 或父部门已被逻辑删除/不存在（孤儿）。后者若丢掉，整棵子树会在响应里静默消失
                roots.add(node);
            }
            else if (parent == node) {
                // 自引用（parent_id = id）是脏数据。不拦的话 node 会成为自己的 child，
                // Jackson 序列化时无限递归 → 请求挂死。这里降级成根节点
                log.warn("[部门树] 检测到自引用脏数据，降级为根节点。id={}", department.getId());
                roots.add(node);
            }
            else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    // ==================== §6.2 创建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DepartmentCreateDTO dto) {
        Long parentId = dto.getParentId();
        Department parent = parentId == ROOT_PARENT_ID ? null : requireParent(parentId);

        Department department = new Department();
        department.setParentId(parentId);
        department.setName(dto.getName().trim());
        department.setSort(dto.getSort() == null ? DEFAULT_SORT : dto.getSort());
        department.setStatus(STATUS_ENABLED);
        // path 必须等自增 id 出来才能算，先放占位值（该列 NOT NULL）
        department.setPath(PATH_PLACEHOLDER);

        departmentService.save(department);

        // 同一事务内回填 path
        String path = buildPath(parent, department.getId());
        Department pathUpdate = new Department();
        pathUpdate.setId(department.getId());
        pathUpdate.setPath(path);
        departmentService.updateById(pathUpdate);

        log.info("[创建部门] id={} name={} parentId={} path={}",
                department.getId(), department.getName(), parentId, path);
        return department.getId();
    }

    // ==================== §6.3 修改（含移动子树） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DepartmentUpdateDTO dto) {
        Department self = requireDepartment(id);
        Long newParentId = dto.getParentId();
        Department newParent = newParentId == ROOT_PARENT_ID ? null : requireParent(newParentId);

        // 成环校验：新父必须不在自己的子树里。
        // 因为 path 含自身，`newParentId == id`（移到自己下面）也会被这条规则拦下
        if (newParent != null && newParent.getPath().startsWith(self.getPath())) {
            log.warn("[修改部门] 被拒：目标父部门在自己的子树内。id={} selfPath={} newParentId={} newParentPath={}",
                    id, self.getPath(), newParentId, newParent.getPath());
            throw BizException.conflict("不能把部门移动到自己的子部门下");
        }

        String oldPath = self.getPath();
        String newPath = buildPath(newParent, id);
        boolean pathChanged = !Objects.equals(oldPath, newPath);

        Department update = new Department();
        update.setId(id);
        update.setParentId(newParentId);
        update.setName(dto.getName().trim());
        update.setSort(dto.getSort() == null ? DEFAULT_SORT : dto.getSort());
        if (pathChanged) {
            update.setPath(newPath);
        }
        departmentService.updateById(update);

        // 自身 path 变了 → 整棵子树的 path 都要跟着换前缀
        if (pathChanged) {
            int moved = rewriteSubtreePaths(id, oldPath, newPath);
            log.info("[修改部门] id={} 父部门 {} → {}，path {} → {}，级联重算 {} 个子孙",
                    id, self.getParentId(), newParentId, oldPath, newPath, moved);
        }
        else {
            log.info("[修改部门] id={} name={} sort={}（父部门未变，path 不动）",
                    id, update.getName(), update.getSort());
        }
    }

    /**
     * 把子树里所有后代的 {@code path} 换前缀。
     *
     * <p>调用时机：<b>自身已经更新完之后</b> —— 那时自身的 path 已是新值，
     * 不会再被 {@code LIKE 旧前缀} 匹配到，所以这里查出来的就是纯粹的后代。
     * 额外的 {@code ne(id)} 是显式保险，避免将来有人调换顺序时把自身的
     * {@code parent_id} 用旧值覆盖回去。
     *
     * @param selfId    被移动的部门 id（从子树查询里排除）
     * @param oldPrefix 该部门原来的 path，形如 {@code /1/2/}
     * @param newPrefix 该部门新的 path，形如 {@code /1/5/2/}
     * @return 被改写的子孙数量
     */
    private int rewriteSubtreePaths(Long selfId, String oldPrefix, String newPrefix) {
        // 常量前缀 LIKE，命中 idx_dept_path。
        // oldPrefix 一定以 "/" 结尾（path 规则保证），所以 /1/2/ 不会误伤 /1/20/
        List<Department> descendants = departmentService.list(new LambdaQueryWrapper<Department>()
                .likeRight(Department::getPath, oldPrefix)
                .ne(Department::getId, selfId));

        if (descendants.isEmpty()) {
            return 0;
        }
        List<Department> updates = new ArrayList<>(descendants.size());
        for (Department descendant : descendants) {
            Department one = new Department();
            one.setId(descendant.getId());
            one.setPath(newPrefix + descendant.getPath().substring(oldPrefix.length()));
            updates.add(one);
        }
        departmentService.updateBatchById(updates);
        return updates.size();
    }

    // ==================== §6.4 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireDepartment(id);

        // ① 有子部门 → 拒（@TableLogic 只算未删除的）
        long childCount = departmentService.count(new LambdaQueryWrapper<Department>()
                .eq(Department::getParentId, id));
        if (childCount > 0) {
            log.warn("[删除部门] 被拒：存在子部门。id={} 子部门数={}", id, childCount);
            throw new BizException(ErrorCode.CONFLICT,
                    "该部门下存在 " + childCount + " 个子部门，禁止删除");
        }

        // ② 有成员 → 拒。已逻辑删除的用户不算成员（@TableLogic 自动带 deleted = 0）
        long memberCount = userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getDepartmentId, id));
        if (memberCount > 0) {
            log.warn("[删除部门] 被拒：部门下有用户。id={} 成员数={}", id, memberCount);
            throw new BizException(ErrorCode.CONFLICT,
                    "该部门下存在 " + memberCount + " 个用户，禁止删除");
        }

        departmentService.removeById(id);
        log.info("[删除部门] id={} 逻辑删除完成（deleted = 1，物理行保留）", id);
    }

    // ==================== 私有工具 ====================

    /**
     * 按父部门算自身 path。
     *
     * @param parent 父部门；{@code null} 表示根部门
     * @param id     自身 ID
     */
    private String buildPath(Department parent, Long id) {
        String parentPath = parent == null ? PATH_SEPARATOR : parent.getPath();
        return parentPath + id + PATH_SEPARATOR;
    }

    /** 取部门，不存在抛 40400 */
    private Department requireDepartment(Long id) {
        Department department = departmentService.getById(id);
        if (department == null) {
            throw BizException.notFound("部门不存在");
        }
        return department;
    }

    /** 取父部门（调用方保证 {@code parentId != 0}），不存在抛 40001 */
    private Department requireParent(Long parentId) {
        Department parent = departmentService.getById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "父部门不存在或已删除");
        }
        return parent;
    }
}
