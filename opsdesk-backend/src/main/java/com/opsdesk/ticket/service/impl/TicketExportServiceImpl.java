package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketExportService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.support.TicketQueryConditions;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单导出实现（补单 · 工单导出，API 文档 §8.6）
 *
 * <h2>执行顺序（顺序即安全边界）</h2>
 * <pre>
 * ① 数据范围（TicketDataScopeHelper）  ← 先加范围，后面的一切都只能在范围内再筛
 * ② 客户端筛选（TicketQueryConditions，与列表同一份代码）
 * ③ 排序白名单 + id tiebreaker
 * ④ LIMIT 10001（多取 1 行用来判断"是否被截断"）
 * ⑤ 批量查姓名（1 条 IN 查询）
 * ⑥ 写 xlsx 到响应流
 * </pre>
 *
 * <h2>🔴 三个必须守住的点</h2>
 * <ol>
 *   <li><b>数据范围不能漏</b>：导出把"一页 10 条"放大成"一万条"，
 *       漏了范围就是 EMPLOYEE 一次拉走全公司工单。而 EMPLOYEE 根本没有
 *       {@code ticket:export} 权限码 —— 真正的风险在 AGENT：他只能看
 *       ①分配给自己的 ∪ ②公共池 ∪ ③本部门创建的，导出必须严格等于这个集合。</li>
 *   <li><b>不能用 {@code Page} 做上限</b>：{@code MybatisPlusConfiguration} 里
 *       {@code pagination.setMaxLimit(500)}，{@code Page.of(1, 10000)} 会被<b>静默截成 500 行</b>。
 *       所以这里用 {@code last("LIMIT 10001")}（常量，不是外部输入）。</li>
 *   <li><b>超上限是截断而不是报错</b>：§8.6 写的是"上限 10000 条"，
 *       用户筛出 12000 条时应该拿到前 10000 条 + 一条 warn 日志，而不是 40001。</li>
 * </ol>
 *
 * <h2>⚠️ {@code page} / {@code size} 被刻意忽略</h2>
 * {@code TicketQuery} 继承了 {@code PageQuery}，但导出不分页。若把 size 用上，
 * 就会出现"导出只有 10 条"——用户会以为是数据丢了，而不是"参数用错了"。
 *
 * <h2>⚠️ 内存里生成，不落磁盘</h2>
 * 10000 行 × 12 列的 {@code XSSFWorkbook} 在几十 MB 级，本项目可接受。
 * 刻意不用 {@code SXSSFWorkbook}（它会把临时文件写到 {@code java.io.tmpdir}），
 * 也不做"异步导出 + 稍后下载"——那会引入任务表、过期清理、下载鉴权一整套东西，
 * 而 10000 行的量级根本不值得。
 */
@Slf4j
@Service
public class TicketExportServiceImpl implements TicketExportService {

    /** §8.6 的导出上限 */
    static final int MAX_EXPORT_ROWS = 10_000;

    private static final String SHEET_NAME = "工单";

    /** 时间列统一格式（§2.6 的 {@code yyyy-MM-dd HH:mm:ss}） */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 列定义（12 列）。
     *
     * <p>⚠️ 枚举一律导出<b>中文 label</b>（{@code P1} → 紧急、{@code OPEN} → 待受理）——
     * 这张表是给人看、被转发给领导的，不是给程序解析的。
     */
    private static final String[] HEADERS = {
            "工单号", "标题", "类型", "分类", "优先级", "状态",
            "创建人", "处理人", "SLA 解决状态", "创建时间", "解决时间", "关闭时间"
    };

    /** 各列宽度（字符数），不用 {@code autoSizeColumn} —— 它要遍历所有单元格，1 万行时明显变慢 */
    private static final int[] COLUMN_WIDTHS = {
            22, 40, 12, 16, 10, 12, 12, 12, 14, 20, 20, 20
    };

    private final TicketService ticketService;
    private final UserService userService;
    private final TicketDataScopeHelper dataScopeHelper;

    public TicketExportServiceImpl(TicketService ticketService,
                                   UserService userService,
                                   TicketDataScopeHelper dataScopeHelper) {
        this.ticketService = ticketService;
        this.userService = userService;
        this.dataScopeHelper = dataScopeHelper;
    }

    @Override
    public int export(TicketQuery query, OutputStream out) {
        TicketQuery q = query == null ? new TicketQuery() : query;

        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Ticket::getId, Ticket::getTicketNo, Ticket::getTitle,
                Ticket::getType, Ticket::getCategory, Ticket::getPriority, Ticket::getStatus,
                Ticket::getCreatorId, Ticket::getAssigneeId, Ticket::getSlaResolutionState,
                Ticket::getCreatedAt, Ticket::getResolvedAt, Ticket::getClosedAt);

        // ① 数据范围 —— 必须走 Helper，且必须是第一步
        dataScopeHelper.applyScope(wrapper, UserContext.get());

        // ② 客户端筛选 + ③ 排序（与工单列表共用同一份条件构造，杜绝"列表 8 条、导出 10 条"）
        TicketQueryConditions.applyFilters(wrapper, q);
        TicketQueryConditions.applySort(wrapper, q.getSort());

        // ④ 多取 1 行：用来判断"是否被截断"，不需要额外跑一次 count(*)
        wrapper.last("LIMIT " + (MAX_EXPORT_ROWS + 1));

        List<Ticket> tickets = ticketService.list(wrapper);
        if (tickets.size() > MAX_EXPORT_ROWS) {
            tickets = tickets.subList(0, MAX_EXPORT_ROWS);
            log.warn("[工单导出] 命中超过上限，已截断到 {} 条。userId={} 筛选={}",
                    MAX_EXPORT_ROWS, UserContext.getUserId(), q);
        }

        // ⑤ 姓名批量查一次（1 条 IN 查询，与行数无关）
        Map<Long, String> displayNames = loadDisplayNames(tickets);

        // ⑥ 写文件
        writeWorkbook(tickets, displayNames, out);

        log.info("[工单导出] 完成 userId={} 行数={} 截断={}",
                UserContext.getUserId(), tickets.size(), tickets.size() >= MAX_EXPORT_ROWS);
        return tickets.size();
    }

    // ==================== Excel ====================

    private void writeWorkbook(List<Ticket> tickets, Map<Long, String> displayNames, OutputStream out) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle bodyStyle = bodyStyle(workbook);

            // 表头 —— 空结果时也要写，否则用户下载到一个空文件会以为"导出坏了"
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (Ticket ticket : tickets) {
                Row row = sheet.createRow(rowIndex++);
                writeCells(row, ticket, displayNames, bodyStyle);
            }

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }
            // 表头冻结：一万行时往下滚还能看见列名
            sheet.createFreezePane(0, 1);

            workbook.write(out);
        }
        catch (IOException ex) {
            // 到这一步响应通常已经提交（部分字节写出去了），前端会看到"下载损坏"。
            // 记 error 带堆栈，便于从日志定位到底是谁在写流时断开
            log.error("[工单导出] 写入响应流失败", ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出失败：生成 Excel 时出错", ex);
        }
    }

    private void writeCells(Row row, Ticket ticket, Map<Long, String> displayNames, CellStyle style) {
        setCell(row, 0, ticket.getTicketNo(), style);
        setCell(row, 1, ticket.getTitle(), style);
        setCell(row, 2, ticket.getType() == null ? null : ticket.getType().getLabel(), style);
        setCell(row, 3, ticket.getCategory() == null ? null : ticket.getCategory().getLabel(), style);
        setCell(row, 4, ticket.getPriority() == null ? null : ticket.getPriority().getLabel(), style);
        setCell(row, 5, ticket.getStatus() == null ? null : ticket.getStatus().getLabel(), style);
        setCell(row, 6, displayNames.get(ticket.getCreatorId()), style);
        setCell(row, 7, ticket.getAssigneeId() == null ? null : displayNames.get(ticket.getAssigneeId()), style);
        setCell(row, 8, ticket.getSlaResolutionState() == null
                ? null : ticket.getSlaResolutionState().getLabel(), style);
        setCell(row, 9, format(ticket.getCreatedAt()), style);
        setCell(row, 10, format(ticket.getResolvedAt()), style);
        setCell(row, 11, format(ticket.getClosedAt()), style);
    }

    private void setCell(Row row, int index, String value, CellStyle style) {
        Cell cell = row.createCell(index);
        // null 写成空单元格而不是字符串 "null" —— 后者会让用户以为数据坏了
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle bodyStyle(XSSFWorkbook workbook) {
        return workbook.createCellStyle();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME);
    }

    // ==================== 姓名 ====================

    /** 整批一次 IN 查询；姓名优先 {@code nickname}，缺失退 {@code username} */
    private Map<Long, String> loadDisplayNames(List<Ticket> tickets) {
        Set<Long> userIds = new HashSet<>();
        for (Ticket ticket : tickets) {
            if (ticket.getCreatorId() != null) {
                userIds.add(ticket.getCreatorId());
            }
            if (ticket.getAssigneeId() != null) {
                userIds.add(ticket.getAssigneeId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, this::displayNameOf, (a, b) -> a));
    }

    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }
}
