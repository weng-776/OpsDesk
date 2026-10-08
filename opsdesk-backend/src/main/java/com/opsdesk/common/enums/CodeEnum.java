package com.opsdesk.common.enums;

/**
 * 带中文标签的枚举（规格基线 §3 术语与枚举表 —— 全项目 SSOT）
 *
 * <p>约定：
 * <ul>
 *   <li>枚举的 <b>常量名即业务 code</b>（§3.13 大写下划线），如 {@code WAITING_CONFIRM}</li>
 *   <li>{@link #getLabel()} 只用于日志与展示，<b>不参与业务判定</b>，也不进接口契约</li>
 *   <li>接口 JSON 里序列化的永远是 code（Jackson 默认按 name 序列化）</li>
 * </ul>
 *
 * <p>AI 结构化输出的枚举字段必须落在这些枚举的白名单内（§10.3 / §11.3），
 * 校验用 {@link com.opsdesk.common.utils.EnumUtils#isValid(Class, String)}。
 */
public interface CodeEnum {

    /** 中文名称，仅用于展示 */
    String getLabel();
}
