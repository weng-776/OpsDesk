package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.TicketExportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单导出验收测试（补单 · 工单导出，API 文档 §8.6）
 *
 * <h2>种子数据与可见集合（与 {@link TicketQueryServiceTest} 同源）</h2>
 * <pre>
 * admin       → {1,2,3,4,5}
 * agent_zhang → {1,2,4}    （①assignee ②公共池 ③本部门）
 * 全部工单 created_at 相同 → 排序实际由 id DESC 决定
 * </pre>
 *
 * <p>整类 {@link Transactional}：只读，跑完回滚。
 */
@SpringBootTest
@Transactional
class TicketExportServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;

    @Autowired
    private TicketExportService ticketExportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("验收1：ADMIN 导出 = 全企业 5 行 + 1 表头（表头 12 列中文）")
    void admin导出全部() {
        Sheet sheet = exportAs(ADMIN, Role.ADMIN, q -> {
        });

        assertThat(rowTexts(sheet, 0)).as("表头")
                .containsExactly("工单号", "标题", "类型", "分类", "优先级", "状态",
                        "创建人", "处理人", "SLA 解决状态", "创建时间", "解决时间", "关闭时间");
        assertThat(dataRowCount(sheet)).as("种子 5 张工单").isEqualTo(5);
        assertThat(rowTexts(sheet, 1).get(0)).as("created_at 全同秒 → id DESC 决定顺序，第一条是 5")
                .isEqualTo("OD2026100600005");
    }

    @Test
    @DisplayName("验收2：AGENT 导出 = 他自己的可见集合 3 行（不是全企业 5 行）")
    void agent导出受数据范围限制() {
        Sheet sheet = exportAs(AGENT_ZHANG, Role.AGENT, q -> {
        });

        assertThat(dataRowCount(sheet)).as("agent_zhang 可见 {4,2,1}").isEqualTo(3);
        assertThat(rowTexts(sheet, 1).get(0)).as("created_at 全同秒 → id DESC：先 4（CLOSED 历史单，编号是 1005 那批）")
                .isEqualTo("OD2026100500001");
        assertThat(rowTexts(sheet, 3).get(0)).isEqualTo("OD2026100600001");
    }

    @Test
    @DisplayName("验收5：筛选生效 —— status=OPEN 只导 1 行")
    void 筛选生效() {
        Sheet sheet = exportAs(ADMIN, Role.ADMIN, q -> q.setStatus(TicketStatus.OPEN));

        assertThat(dataRowCount(sheet)).isEqualTo(1);
        assertThat(rowTexts(sheet, 1).get(0)).isEqualTo("OD2026100600001");
        assertThat(rowTexts(sheet, 1).get(5)).as("状态列是中文 label，不是 OPEN").isEqualTo("待受理");
    }

    @Test
    @DisplayName("验收7：空结果 → 200 语义 + 只有表头（不是空文件、不是 404）")
    void 空结果仍有表头() {
        Sheet sheet = exportAs(ADMIN, Role.ADMIN, q -> q.setKeyword("绝对不存在的关键字zzz"));

        assertThat(dataRowCount(sheet)).as("没有数据行").isZero();
        assertThat(rowTexts(sheet, 0)).as("表头照样在").hasSize(12);
    }

    @Test
    @DisplayName("验收8：page/size 被忽略 —— size=1 仍导出 5 行（不是只导 1 行）")
    void 分页参数不生效() {
        Sheet sheet = exportAs(ADMIN, Role.ADMIN, q -> {
            q.setPage(2L);
            q.setSize(1L);
        });

        assertThat(dataRowCount(sheet)).as("导出不分页").isEqualTo(5);
    }

    @Test
    @DisplayName("验收9：类型 / 优先级 / 状态都是中文 label，时间是 yyyy-MM-dd HH:mm:ss")
    void 枚举与时间格式正确() {
        Sheet sheet = exportAs(ADMIN, Role.ADMIN, q -> q.setStatus(TicketStatus.CLOSED));

        Row row = sheet.getRow(1);
        assertThat(row.getCell(2).getStringCellValue()).as("类型").isIn("故障异常", "服务请求");
        assertThat(row.getCell(4).getStringCellValue()).as("优先级").isIn("紧急", "高", "普通");
        assertThat(row.getCell(5).getStringCellValue()).isEqualTo("已关闭");
        assertThat(row.getCell(9).getStringCellValue()).as("创建时间格式")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("非法 sort 仍然 40001（导出与列表共用同一份白名单）")
    void 非法排序被拒() {
        assertThat(catchBiz(() -> exportAs(ADMIN, Role.ADMIN, q -> q.setSort("id;DROP TABLE ticket"))))
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ==================== 辅助 ====================

    private Sheet exportAs(long userId, Role role, Consumer<TicketQuery> customizer) {
        TicketQuery query = new TicketQuery();
        customizer.accept(query);
        UserContext.set(new UserContext.CurrentUser(
                userId, "test-jti", Set.of(role), Set.of(), departmentIdOf(userId)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ticketExportService.export(query, out);
        }
        finally {
            UserContext.clear();
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            return workbook.getSheetAt(0);
        }
        catch (IOException ex) {
            throw new IllegalStateException("导出的字节流不是合法 xlsx", ex);
        }
    }

    /** 数据行数 = 末行下标（0 是表头） */
    private int dataRowCount(Sheet sheet) {
        return Math.max(sheet.getLastRowNum(), 0);
    }

    private java.util.List<String> rowTexts(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            values.add(row.getCell(i) == null ? "" : row.getCell(i).getStringCellValue());
        }
        return values;
    }

    private Long departmentIdOf(long userId) {
        java.util.List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static ErrorCode catchBiz(Runnable action) {
        try {
            action.run();
            return null;
        }
        catch (BizException ex) {
            return ex.getErrorCode();
        }
    }
}
