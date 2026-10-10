package com.opsdesk.audit.service;

import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;

/**
 * 审计记录服务（工单 D6-01）—— <b>写 {@code audit_log} 的唯一出口</b>
 *
 * <p>切面只负责「什么时候记」，快照怎么取、怎么截断、ip/ua 怎么来，全部收口在这里。
 *
 * <h2>⚠️ 本接口/实现<b>不得</b>加 {@code @AuditLog}</h2>
 * 审计自己的写入方法若被审计，就是自指递归（记一次审计 → 触发一次审计 → …）。
 * 切点只匹配带注解的方法，所以只要不标就安全 —— 但这是个容易手滑的地方，写在这里提醒。
 *
 * <h2>⚠️ 不可篡改（§14.3）</h2>
 * 本项目<b>不提供</b>任何修改 / 删除审计日志的接口。
 * {@code AuditLogService}（生成类）技术上带 {@code remove*} 方法，但没有任何 HTTP 入口，
 * 后续也不得新增。
 */
public interface AuditRecordService {

    /**
     * 没有登录上下文时的操作人 id。
     *
     * <p>{@code audit_log.user_id} 是 {@code NOT NULL}，而定时任务 / MQ 消费者等场景
     * 本来就没有登录用户。DDL 上没有外键，{@code 0} 又不与任何真实用户冲突，
     * 所以用它表示<b>系统操作</b>（比"丢掉这条审计"更符合 §14.3 的「优先保证不丢」）。
     */
    long SYSTEM_USER_ID = 0L;

    /**
     * 取一份快照并序列化为 JSON（已按 §14.3 截断）。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源 id；为 {@code null} 时直接返回 {@code null}
     * @return 可直接入库的 JSON 文本；资源不存在、或该类型还没注册 provider 时返回 {@code null}
     */
    String snapshot(AuditResourceType resourceType, Long resourceId);

    /**
     * 落一条审计。
     *
     * <p>⚠️ <b>不吞异常</b>：写入失败直接向上抛，让业务事务一起回滚 ——
     * 这是 §14.3「与业务同事务同步写，优先保证不丢」的直译
     * （宁可这次操作不做，也不要「改了但没有审计」）。
     * 为了让它几乎不可能失败，快照侧做了三处防御：字段截断 2000 / UA 截 255 / 整体 JSON 兜底。
     *
     * @param operation    操作码
     * @param resourceType 资源类型
     * @param resourceId   资源 id；可为 {@code null}
     * @param beforeData   变更前快照 JSON；可为 {@code null}
     * @param afterData    变更后快照 JSON；可为 {@code null}
     */
    void record(AuditOperation operation, AuditResourceType resourceType, Long resourceId,
                String beforeData, String afterData);
}
