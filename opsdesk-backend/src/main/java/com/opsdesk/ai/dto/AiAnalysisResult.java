package com.opsdesk.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * 模型返回的结构化结果（工单 D10-02，规格基线 §10.1 的输出格式）
 *
 * <pre>
 * {"type":"INCIDENT","category":"NETWORK","priority":"P1",
 *  "summary":"VPN连接异常","suggestions":["检查 VPN 客户端状态","检查网络连接"]}
 * </pre>
 *
 * <h2>🔴 为什么 {@code type} / {@code category} / {@code priority} 是 {@code String} 而不是枚举</h2>
 * 这是本单最容易写错的一处。如果把它们声明成 {@code TicketCategory} 这样的枚举：
 * <ul>
 *   <li>模型返回一个非法值（如 {@code HACK}）时，<b>Jackson 反序列化阶段就会抛异常</b>；</li>
 *   <li>这个异常会被归到「JSON 解析失败」那一类 → 按 §10.3 走<b>重试 + FAILED</b>；</li>
 *   <li>但 §10.3 对「枚举不在白名单」的要求是<b>回退 + 告警，且状态仍可 SUCCESS</b>。</li>
 * </ul>
 * 两条路径的语义完全不同 —— 用枚举会让「可回退的越界」退化成「整体失败」，
 * 白白丢掉一次本该可用的分析结果（模型只是某个字段猜错了，其它字段都是好的）。
 *
 * <p>所以这里用 {@code String} 接收，<b>由代码自己 {@code valueOf} 校验</b>，
 * 非法值回退为原工单值并记告警（见 {@code TicketAnalyzeServiceImpl#resolve}）。
 *
 * <p>⚠️ 不允许改用「字符串数组比对」做校验（§0 约定：禁止散落字符串字面量）——
 * 白名单的唯一来源是 {@code com.opsdesk.common.enums} 里的枚举。
 */
@Data
public class AiAnalysisResult {

    /** 建议类型：INCIDENT / SERVICE_REQUEST（用 String 接收，见类注释） */
    private String type;

    /** 建议分类：NETWORK / ACCOUNT / HARDWARE / SOFTWARE / EMAIL / PERIPHERAL / OTHER */
    private String category;

    /** 建议优先级：P1 / P2 / P3 */
    private String priority;

    /** 问题摘要（⚠️ 入库前要截断到 {@code summary} 列的 512 字符） */
    private String summary;

    /** 处理建议（§10.3：为空<b>允许</b>，不算失败） */
    private List<String> suggestions;
}
