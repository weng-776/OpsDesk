package com.opsdesk.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.audit.dto.AuditLogQuery;
import com.opsdesk.audit.entity.AuditLog;
import com.opsdesk.audit.service.AuditLogQueryService;
import com.opsdesk.audit.service.AuditLogService;
import com.opsdesk.audit.service.AuditRecordService;
import com.opsdesk.audit.vo.AuditLogVO;
import com.opsdesk.common.PageResult;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计日志查询实现（工单 D6-02，API 文档 §13.1）
 *
 * <h2>为什么不走 mapper XML</h2>
 * SOP §5 要求「自定义 SQL（JOIN / 聚合 / 复杂条件）走 XML」。这里需要 {@code userName}，
 * 看起来像要 JOIN {@code user} —— 但项目的<b>分页列表</b>一律不 JOIN：先按条件分页查出本页
 * 记录，再用 {@code listByIds} 批量补姓名（见 {@code TicketQueryServiceImpl.loadDisplayNames}）。
 * 这样「一页 50 行」只多一次查询，且完全不写自定义 SQL —— 于是也不需要 XML。
 * 附件/历史那种「非分页的简单列表」才用 XML JOIN，两者分工是既有的。
 *
 * <h2>索引命中</h2>
 * 筛选条件与排序方向都按 §18.4 的三个索引最左前缀设计：
 * {@code idx_audit_resource(resource_type, resource_id, created_at)}、
 * {@code idx_audit_user(user_id, created_at)}、{@code idx_audit_op(operation, created_at)}。
 * ⚠️ 排序必须带 {@code id DESC} 做 tiebreaker —— 审计行可能同一秒写入多条
 * （一次请求里业务 + 审计、或批量操作），只按 {@code created_at} 排序 MySQL 不保证稳定 → 翻页跳行。
 */
@Slf4j
@Service
public class AuditLogQueryServiceImpl implements AuditLogQueryService {

    /** 系统操作的展示名（{@code user_id = 0}，见 {@link AuditRecordService#SYSTEM_USER_ID}） */
    private static final String SYSTEM_USER_NAME = "系统";

    private final AuditLogService auditLogService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AuditLogQueryServiceImpl(AuditLogService auditLogService,
                                    UserService userService,
                                    ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<AuditLogVO> page(AuditLogQuery query) {
        long pageNo = query.pageOrDefault();
        long size = query.sizeOrDefault();

        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        // 只取 VO 需要的列（既不是 SELECT *，也不多捞）
        wrapper.select(AuditLog::getId, AuditLog::getUserId, AuditLog::getOperation,
                AuditLog::getResourceType, AuditLog::getResourceId,
                AuditLog::getBeforeData, AuditLog::getAfterData,
                AuditLog::getIp, AuditLog::getUserAgent, AuditLog::getCreatedAt);

        applyFilters(wrapper, query);

        // 最新在前；id 做 tiebreaker（同一秒多行时保证翻页稳定）
        wrapper.orderByDesc(AuditLog::getCreatedAt).orderByDesc(AuditLog::getId);

        Page<AuditLog> page = auditLogService.page(new Page<>(pageNo, size), wrapper);
        log.debug("[审计查询] page={} size={} total={} 命中 {} 行",
                pageNo, size, page.getTotal(), page.getRecords().size());

        return PageResult.of(toVoList(page.getRecords()), page.getTotal(), pageNo, size);
    }

    // ==================== 私有 ====================

    /**
     * 各筛选条件都是可选的（不传即不筛）。
     *
     * <p>⚠️ 条件用 {@code eq} 而不是 {@code like}：审计是追责用的，
     * 模糊匹配会把不相关的记录混进来。用户要模糊查，前端传精确值即可。
     */
    private void applyFilters(LambdaQueryWrapper<AuditLog> wrapper, AuditLogQuery query) {
        if (query.getUserId() != null) {
            wrapper.eq(AuditLog::getUserId, query.getUserId());
        }
        if (query.getOperation() != null) {
            wrapper.eq(AuditLog::getOperation, query.getOperation());
        }
        if (query.getResourceType() != null) {
            wrapper.eq(AuditLog::getResourceType, query.getResourceType());
        }
        if (query.getResourceId() != null) {
            wrapper.eq(AuditLog::getResourceId, query.getResourceId());
        }
        // 闭区间：startTime <= created_at <= endTime
        if (query.getStartTime() != null) {
            wrapper.ge(AuditLog::getCreatedAt, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(AuditLog::getCreatedAt, query.getEndTime());
        }
    }

    private List<AuditLogVO> toVoList(List<AuditLog> rows) {
        Map<Long, String> userNames = loadUserNames(rows);
        return rows.stream().map(row -> toVo(row, userNames)).toList();
    }

    /** 本页涉及的 {@code user_id} 去重后一次查回姓名（避免 N+1） */
    private Map<Long, String> loadUserNames(List<AuditLog> rows) {
        Set<Long> userIds = new HashSet<>();
        for (AuditLog row : rows) {
            // 0 是「系统」，没有对应的用户行，不必去查
            if (row.getUserId() != null && row.getUserId() != AuditRecordService.SYSTEM_USER_ID) {
                userIds.add(row.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, this::displayNameOf));
    }

    private AuditLogVO toVo(AuditLog row, Map<Long, String> userNames) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setUserName(resolveUserName(row.getUserId(), userNames));
        vo.setOperation(row.getOperation());
        vo.setResourceType(row.getResourceType());
        vo.setResourceId(row.getResourceId());
        vo.setBeforeData(parseSnapshot(row.getId(), "before_data", row.getBeforeData()));
        vo.setAfterData(parseSnapshot(row.getId(), "after_data", row.getAfterData()));
        vo.setIp(row.getIp());
        vo.setUserAgent(row.getUserAgent());
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    private String resolveUserName(Long userId, Map<Long, String> userNames) {
        if (userId == null) {
            return null;
        }
        if (userId == AuditRecordService.SYSTEM_USER_ID) {
            return SYSTEM_USER_NAME;
        }
        // 用户被删掉后姓名查不到 —— 返回 null，不编造
        return userNames.get(userId);
    }

    /**
     * 把库里存的 JSON 文本解析成 {@link JsonNode}（§16.16 要求这两个字段是 object）。
     *
     * <p>解析失败只记 warn 并返回 {@code null}：审计查询不该因为一条脏数据整页失败。
     */
    private JsonNode parseSnapshot(Long auditId, String column, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        }
        catch (Exception ex) {
            log.warn("[审计查询] {} 不是合法 JSON，按 null 返回。auditId={}", column, auditId, ex);
            return null;
        }
    }

    /** 姓名优先用 nickname，缺失时退回 username（与工单列表/附件/历史四处口径一致） */
    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }
}
