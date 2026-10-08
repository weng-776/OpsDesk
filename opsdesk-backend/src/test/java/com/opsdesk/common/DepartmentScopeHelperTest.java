package com.opsdesk.common;

import com.opsdesk.common.datascope.DepartmentScopeHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 部门子树解析验收测试（D2-04 抽取的公共实现）
 *
 * <p>规格依据：`OpsDesk_DDL_V1.sql` 的 {@code department.path} 注释（祖先路径含自身）、
 * 规格基线 §8.3（靠 path 前缀匹配实现含子部门递归）、API 文档 §5.1（「含子部门」筛选）。
 *
 * <h2>为什么这个类值得单独测</h2>
 * 「按 path 常量前缀取子树」被三处复用（D1-03 用户筛选 / D1-04 重算 path / D2-04 工单范围）。
 * 它写歪不会报错，只会<b>静默漏数据</b>（某些人/某些工单看不见）。
 * 所以这里把两个最危险的细节钉死：
 * <ul>
 *   <li><b>前缀必须含末尾 {@code /}</b>：{@code /1/3} 会匹配到 {@code /1/30/}（另一个部门！）
 *       → 多放范围进来，属越权方向的错误</li>
 *   <li><b>结果含自身</b>：{@code path} 是「含自身」的祖先路径，所以技术部的子树是
 *       {@code [2,3,4]}（技术部自己 + 后端组 + 前端组）</li>
 * </ul>
 *
 * <p>整类 {@link Transactional}：造的探针部门跑完自动回滚。
 */
@SpringBootTest
@Transactional
class DepartmentScopeHelperTest {

    /** 种子部门：1 示例科技(/1/) 2 技术部(/1/2/) 3 后端组(/1/2/3/) 4 前端组(/1/2/4/) 5 财务部(/1/5/) 6 人事部(/1/6/) */
    private static final long ROOT = 1L;
    private static final long TECH = 2L;
    private static final long BACKEND = 3L;
    private static final long FINANCE = 5L;

    @Autowired
    private DepartmentScopeHelper departmentScopeHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== subtreeIds ====================

    @Test
    @DisplayName("技术部(2) 的子树 = [2,3,4]（含自身 + 两个子部门）")
    void 子树含自身与子部门() {
        assertThat(departmentScopeHelper.subtreeIds(TECH))
                .as("path=/1/2/ → 命中 /1/2/、/1/2/3/、/1/2/4/")
                .containsExactly(TECH, BACKEND, 4L);
    }

    @Test
    @DisplayName("叶子部门(3) 的子树只有自己")
    void 叶子子树只有自己() {
        assertThat(departmentScopeHelper.subtreeIds(BACKEND)).containsExactly(BACKEND);
    }

    @Test
    @DisplayName("根部门(1) 的子树是全部 6 个部门")
    void 根子树是全部() {
        assertThat(departmentScopeHelper.subtreeIds(ROOT)).hasSize(6).contains(ROOT, TECH, FINANCE);
    }

    @Test
    @DisplayName("部门不存在 / 入参为 null → 空列表（调用方必须短路，否则 IN () 语法错）")
    void 不存在或null返回空() {
        assertThat(departmentScopeHelper.subtreeIds(999_999L)).isEmpty();
        assertThat(departmentScopeHelper.subtreeIds(null)).isEmpty();
    }

    // ==================== pathOf ====================

    @Test
    @DisplayName("pathOf：技术部 → /1/2/；不存在 / null → null")
    void pathOf查询() {
        assertThat(departmentScopeHelper.pathOf(TECH)).isEqualTo("/1/2/");
        assertThat(departmentScopeHelper.pathOf(ROOT)).isEqualTo("/1/");
        assertThat(departmentScopeHelper.pathOf(999_999L)).isNull();
        assertThat(departmentScopeHelper.pathOf(null)).isNull();
    }

    // ==================== subtreeByPath ====================

    @Test
    @DisplayName("subtreeByPath 的结果【包含】该部门自己（path 含自身）")
    void 按路径取子树包含自身() {
        assertThat(departmentScopeHelper.subtreeByPath("/1/2/"))
                .extracting(d -> d.getId())
                .containsExactly(TECH, BACKEND, 4L);
    }

    @Test
    @DisplayName("前缀不以 / 结尾 → 抛 IllegalArgumentException（宁可吵，也不要静默多放范围）")
    void 前缀必须以斜杠结尾() {
        // /1/3 会匹配到 /1/30/ —— 另一个部门的子树会被误并进来
        assertThatThrownBy(() -> departmentScopeHelper.subtreeByPath("/1/3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须以 '/' 结尾");
    }

    @Test
    @DisplayName("空 / null 前缀 → 空列表（不查库）")
    void 空前缀返回空() {
        assertThat(departmentScopeHelper.subtreeByPath(null)).isEmpty();
        assertThat(departmentScopeHelper.subtreeByPath("")).isEmpty();
        assertThat(departmentScopeHelper.subtreeByPath("   ")).isEmpty();
    }

    @Test
    @DisplayName("前缀不误伤兄弟部门：/1/2/3/ 匹配不到 /1/2/30/")
    void 前缀不误伤兄弟部门() {
        // 后端组(3) 的 path 是 /1/2/3/（父是技术部，不是根），所以造一个 path=/1/2/30/ 的探针：
        // 它的 path 以 /1/2/3 开头，但**不是** /1/2/3/ 的子树 —— 差的就是那个斜杠
        jdbcTemplate.update("INSERT INTO department (parent_id, name, path, sort, status, deleted) "
                + "VALUES (?, ?, ?, 0, 1, 0)", TECH, "probe_1_2_30", "/1/2/30/");
        Long probeId = jdbcTemplate.queryForObject(
                "SELECT id FROM department WHERE path = '/1/2/30/'", Long.class);

        // 先证明这条断言不是空验证：不带末尾斜杠的 LIKE 确实会把探针也匹配进来
        Integer withoutSlash = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM department WHERE deleted = 0 AND path LIKE '/1/2/3%'", Integer.class);
        Integer withSlash = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM department WHERE deleted = 0 AND path LIKE '/1/2/3/%'", Integer.class);
        assertThat(withoutSlash).as("漏掉末尾斜杠 → 会多匹配到 /1/2/30/").isEqualTo(2);
        assertThat(withSlash).as("带上末尾斜杠 → 只有后端组").isEqualTo(1);

        // 所以 subtreeByPath 只应返回后端组，不能把 /1/2/30/ 的探针并进来
        assertThat(departmentScopeHelper.subtreeByPath("/1/2/3/"))
                .extracting(d -> d.getId())
                .containsExactly(BACKEND);
        assertThat(departmentScopeHelper.subtreeByPath("/1/2/3/"))
                .as("探针部门确实存在（否则上面那条是空验证）")
                .extracting(d -> d.getId())
                .doesNotContain(probeId);
    }

    // ==================== 与「含子部门」筛选的语义一致性 ====================

    @Test
    @DisplayName("subtreeIds 与 subtreeByPath 的结果一致（同一个前缀，两种出口不能有分歧）")
    void 两种出口结果一致() {
        for (long deptId : new long[]{ROOT, TECH, BACKEND, FINANCE}) {
            List<Long> byId = departmentScopeHelper.subtreeIds(deptId);
            List<Long> byPath = departmentScopeHelper.subtreeByPath(departmentScopeHelper.pathOf(deptId))
                    .stream().map(d -> d.getId()).toList();
            assertThat(byId).as("部门 %s", deptId).isEqualTo(byPath);
        }
    }
}
