package com.opsdesk.audit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.opsdesk.audit.entity.AuditLog;
import com.opsdesk.audit.service.AuditRecordService;
import com.opsdesk.audit.service.AuditRequestContext;
import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.audit.service.AuditLogService;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 审计记录服务实现（工单 D6-01，规格基线 §14.3）
 *
 * <h2>三处截断，缺一处就可能 insert 失败</h2>
 * <table>
 *   <tr><th>位置</th><th>规则</th><th>不做的后果</th></tr>
 *   <tr><td>快照里每个字符串字段</td><td>2000 字符（§14.3）</td><td>违反规格</td></tr>
 *   <tr><td>整个 JSON</td><td>&gt; 60000 字符时换成占位对象</td>
 *       <td>{@code before_data} 是 {@code TEXT}（65535 <b>字节</b>，中文 3 字节/字）→ insert 失败</td></tr>
 *   <tr><td>{@code user_agent}</td><td>255 字符（列宽）</td><td>长 UA 直接 insert 失败</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么截的是「字段」而不是「整段 JSON」</h2>
 * 把序列化后的 JSON 直接 {@code substring(0, 2000)} 会<b>切出非法 JSON</b> ——
 * 审计数据从此无法解析，比截断本身更糟。
 * 所以先转成 {@link JsonNode} 树、逐字段截断、再序列化，保证 JSON 始终合法。
 * 只有「整段超过 TEXT 容量」这种极端情况才退化成占位对象（并记 warn）。
 */
@Slf4j
@Service
public class AuditRecordServiceImpl implements AuditRecordService {

    /** §14.3：`description` 等长文本截断至 2000 字符 */
    private static final int MAX_TEXT_LENGTH = 2000;

    /**
     * 整个快照 JSON 的安全上限。
     *
     * <p>{@code before_data} / {@code after_data} 是 {@code TEXT} = 65535 <b>字节</b>；
     * 表是 utf8mb4，一个中文字符最多 3 字节，所以按「字符数 60000」留足余量。
     */
    private static final int MAX_JSON_LENGTH = 60_000;

    /** {@code resourceType -> provider}；用 {@link EnumMap} 保证枚举索引高效且顺序稳定 */
    private final Map<AuditResourceType, AuditSnapshotProvider> providers;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final AuditRequestContext requestContext;

    public AuditRecordServiceImpl(List<AuditSnapshotProvider> snapshotProviders,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper,
                                  AuditRequestContext requestContext) {
        Map<AuditResourceType, AuditSnapshotProvider> index =
                new EnumMap<>(AuditResourceType.class);
        for (AuditSnapshotProvider provider : snapshotProviders) {
            AuditSnapshotProvider previous = index.put(provider.resourceType(), provider);
            if (previous != null) {
                // 同类型注册两个 provider 是配置错误：谁生效取决于 Bean 顺序，必须显式报出来
                log.warn("[审计] 资源类型 {} 注册了多个快照提供者，后一个生效：{} 覆盖 {}",
                        provider.resourceType(), provider.getClass().getSimpleName(),
                        previous.getClass().getSimpleName());
            }
        }
        this.providers = Collections.unmodifiableMap(index);
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
        log.info("[审计] 已注册快照提供者：{}", this.providers.keySet());
    }

    @Override
    public String snapshot(AuditResourceType resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            return null;
        }
        AuditSnapshotProvider provider = providers.get(resourceType);
        if (provider == null) {
            // 该资源类型还没做埋点 —— 降级为「只记操作码与操作人」，不阻断业务
            log.warn("[审计] 资源类型 {} 还没有快照提供者，before/after 留空（补一个 provider 即可）",
                    resourceType);
            return null;
        }
        Object data = provider.snapshot(resourceId);
        if (data == null) {
            // 资源不存在：创建类操作在「执行前」查不到东西，属于正常情况
            return null;
        }
        return toJson(data);
    }

    @Override
    public void record(AuditOperation operation, AuditResourceType resourceType, Long resourceId,
                       String beforeData, String afterData) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            userId = SYSTEM_USER_ID;
            log.warn("[审计] 当前线程没有登录上下文，operation={} 记为系统操作（user_id={}）",
                    operation, SYSTEM_USER_ID);
        }

        AuditLog entity = new AuditLog();
        entity.setUserId(userId);
        entity.setOperation(operation);
        entity.setResourceType(resourceType);
        entity.setResourceId(resourceId);
        entity.setBeforeData(beforeData);
        entity.setAfterData(afterData);
        entity.setIp(requestContext.currentIp());
        entity.setUserAgent(requestContext.currentUserAgent());

        // ⚠️ 刻意不 try/catch：写入失败要让业务事务一起回滚（§14.3「优先保证不丢」）。
        //    created_at 是 DB 默认值（实体上是 insertStrategy = NEVER），不需要回填。
        auditLogService.save(entity);

        log.info("[审计] operation={} resourceType={} resourceId={} operator={} beforeSize={} afterSize={}",
                operation, resourceType, resourceId, userId,
                beforeData == null ? 0 : beforeData.length(),
                afterData == null ? 0 : afterData.length());
    }

    // ==================== 私有 ====================

    /**
     * 序列化快照，并按 §14.3 截断。
     *
     * <p>⚠️ 序列化失败<b>不降级</b>：那是代码 bug（比如某个字段类型 ObjectMapper 处理不了），
     * 静默写一条没有快照的审计等于制造「看起来成功了但没记录」的假象。
     * 既然 §14.3 选择「同事务、优先保证不丢」，这里就一路 fail-closed。
     */
    private String toJson(Object data) {
        JsonNode node;
        String json;
        try {
            node = objectMapper.valueToTree(data);
            truncateLongText(node);
            json = objectMapper.writeValueAsString(node);
        }
        catch (Exception ex) {
            log.error("[审计] 快照序列化失败", ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "审计快照生成失败");
        }

        if (json.length() > MAX_JSON_LENGTH) {
            // 极端情况：字段太多 / 太杂。宁可丢内容也不能让 insert 失败（TEXT 只有 64KB）。
            log.warn("[审计] 快照 JSON 长度 {} 超过上限 {}，替换为占位对象",
                    json.length(), MAX_JSON_LENGTH);
            return "{\"_truncated\":true,\"_size\":" + json.length() + "}";
        }
        return json;
    }

    /** 递归把树里超过 {@link #MAX_TEXT_LENGTH} 的文本节点截断（§14.3） */
    private void truncateLongText(JsonNode node) {
        if (node instanceof ObjectNode object) {
            // 先取名字快照再改：边遍历边 set 同一个对象会破坏迭代器
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child instanceof TextNode text && text.asText().length() > MAX_TEXT_LENGTH) {
                    object.set(name, TextNode.valueOf(
                            text.asText().substring(0, MAX_TEXT_LENGTH)));
                    log.debug("[审计] 字段 {} 超长被截断至 {} 字符", name, MAX_TEXT_LENGTH);
                }
                else {
                    truncateLongText(child);
                }
            }
        }
        else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child instanceof TextNode text && text.asText().length() > MAX_TEXT_LENGTH) {
                    array.set(i, TextNode.valueOf(text.asText().substring(0, MAX_TEXT_LENGTH)));
                }
                else {
                    truncateLongText(child);
                }
            }
        }
    }
}
