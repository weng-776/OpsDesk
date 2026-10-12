package com.opsdesk.ai.dto;

import lombok.Data;

/**
 * 采纳 AI 建议的入参（API 文档 §8.14）
 *
 * <pre>
 * { "adoptCategory": true, "adoptPriority": false }   // 都默认 false
 * </pre>
 *
 * <h2>⚠️ 两个开关都默认 {@code false} —— 这是 §10.2 的直接体现</h2>
 * §10.2 的原文是「AI 结果是<b>建议</b>，供 AGENT 参考，<b>不自动覆盖工单字段</b>」。
 * 所以入参做成<b>显式勾选</b>：不传、传 {@code false}、或两个都 {@code false}，
 * 结果都是「工单一个字段都不改」。
 *
 * <p>默认值不是随手写的 —— 它保证「调用方忘了传参」时退化成<b>安全行为</b>（不改），
 * 而不是危险行为（把 AI 的建议当成事实写进工单）。
 *
 * <h2>⚠️ 为什么只有两个开关，没有 {@code type}</h2>
 * §8.14 的 DTO 就这两个。{@code type}（INCIDENT / SERVICE_REQUEST）会牵动状态机与 SLA 规则，
 * 规格没有给它采纳入口，所以<b>不要自己加</b>。
 */
@Data
public class AiAdoptDTO {

    /** 是否采纳 AI 的分类建议；默认 false（不改） */
    private boolean adoptCategory = false;

    /** 是否采纳 AI 的优先级建议；默认 false（不改） */
    private boolean adoptPriority = false;
}
