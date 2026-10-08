package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 优先级（规格基线 §3.4）
 *
 * <p>⚠️ 表里的「响应时限 / 解决时限」是 **MVP 默认策略值，不是枚举常量的一部分** ——
 * 它们落在 {@code sla_policy} 表里，可在 SLA 配置页调整且**策略版本化**（§9.1 / §9.7）。
 * 所以本枚举只保留 code 与名称，**不要把时限硬编码进来**。
 */
@Getter
public enum TicketPriority implements CodeEnum {

    /** 紧急：默认响应 30 分钟 / 解决 4 小时 */
    P1("紧急"),

    /** 高：默认响应 2 小时 / 解决 8 小时 */
    P2("高"),

    /** 普通：默认响应 8 小时 / 解决 24 小时 */
    P3("普通");

    private final String label;

    TicketPriority(String label) {
        this.label = label;
    }
}
