package com.opsdesk.common.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 部门子树解析（D2-04 抽取；收敛 D1-03 / D1-04 的重复实现）
 *
 * <h2>为什么需要它</h2>
 * 「按 {@code department.path} 常量前缀取整棵子树」这个动作，项目里已经写了三遍：
 * <ul>
 *   <li>D1-03 {@code UserManageServiceImpl#resolveDepartmentSubtreeIds} —— 用户列表的「含子部门」筛选</li>
 *   <li>D1-04 {@code DepartmentManageServiceImpl#rewriteSubtreePaths} —— 移动部门时重算子树 path</li>
 *   <li>D2-04 {@code TicketDataScopeHelper} —— 工单数据范围 §8.3 的 ③</li>
 * </ul>
 * 三份代码的 {@code LIKE} 条件、前缀必须以 {@code /} 结尾这些细节只要有一份写歪，
 * 就会<b>静默漏数据</b>（不报错，只是某些工单/用户看不见）。所以收敛到这里一份。
 *
 * <h2>{@code path} 的语义（DDL 注释：祖先路径含自身，如 {@code /1/3/}）</h2>
 * <pre>
 * 根部门：/1/
 * 子部门：父.path + id + "/"    →  /1/2/、/1/2/3/
 * </pre>
 * 因为 {@code path} <b>含自身</b>，对某个部门的 path 做常量前缀 LIKE 就正好覆盖
 * 「自己 + 整棵子树」，且命中索引 {@code idx_dept_path}。
 *
 * <p>⚠️ <b>前缀必须以 {@code /} 结尾</b>：{@code /1/2} 会同时匹配 {@code /1/2/} 与
 * {@code /1/20/}（另一个部门！）。所以 {@link #subtreeByPath} 对这个前提做了显式断言 ——
 * 宁可抛异常，也不要静默多放数据进来。
 *
 * <p>⚠️ <b>分层说明</b>：本类在 {@code common}，却依赖 {@code organization} 的
 * {@code DepartmentService} —— 这是 SOP §5 把 DataScopeHelper 归 {@code common} 带来的
 * 既有取舍（它必须读 {@code department} 表）。不是疏漏，但确实是 common → organization 的
 * 反向依赖，记录备查。
 */
@Component
public class DepartmentScopeHelper {

    /** path 分隔符（DDL：祖先路径含自身，如 {@code /1/3/}） */
    private static final String PATH_SEPARATOR = "/";

    private final DepartmentService departmentService;

    public DepartmentScopeHelper(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /**
     * 部门 id → {@code path}。
     *
     * @return 部门不存在（或已逻辑删除）返回 {@code null}
     */
    public String pathOf(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentService.getById(departmentId);
        return department == null ? null : department.getPath();
    }

    /**
     * 按常量 {@code path} 前缀取整棵子树。
     *
     * <p><b>结果包含该部门自己</b>（因为 {@code path} 含自身）。
     *
     * <pre>SELECT … FROM department WHERE deleted = 0 AND path LIKE '/1/2/%' ORDER BY id</pre>
     *
     * @param pathPrefix 形如 {@code /1/2/} 的祖先路径，<b>必须以 {@code /} 结尾</b>
     * @throws IllegalArgumentException 前缀没有以 {@code /} 结尾（会静默多匹配兄弟部门）
     */
    public List<Department> subtreeByPath(String pathPrefix) {
        if (!StringUtils.hasText(pathPrefix)) {
            return List.of();
        }
        if (!pathPrefix.endsWith(PATH_SEPARATOR)) {
            // /1/2 会匹配到 /1/20/ —— 多放一个部门的范围进来，属于越权方向的错误，必须吵
            throw new IllegalArgumentException(
                    "path 前缀必须以 '/' 结尾，否则会误伤兄弟部门：" + pathPrefix);
        }
        return departmentService.list(new LambdaQueryWrapper<Department>()
                .likeRight(Department::getPath, pathPrefix)
                .orderByAsc(Department::getId));
    }

    /**
     * 部门 id → 整棵子树的 id 列表（<b>含自身</b>）。
     *
     * <p>用于把「本部门含子部门」翻译成 {@code xxx_id IN (…)}，调用方：
     * {@code user.department_id IN (…)}（D1-03）、{@code ticket.department_id IN (…)}（D2-04）。
     *
     * @return 部门不存在 / 入参为 {@code null} → 空列表（调用方必须对空列表短路，
     *         否则 {@code IN ()} 是 SQL 语法错）
     */
    public List<Long> subtreeIds(Long departmentId) {
        if (departmentId == null) {
            return List.of();
        }
        Department department = departmentService.getById(departmentId);
        if (department == null) {
            return List.of();
        }
        String path = department.getPath();
        if (!StringUtils.hasText(path)) {
            // path 异常时退化成「只筛本部门」，而不是把整表放出去（fail-closed 方向）
            return List.of(department.getId());
        }
        return subtreeByPath(path).stream().map(Department::getId).toList();
    }
}
