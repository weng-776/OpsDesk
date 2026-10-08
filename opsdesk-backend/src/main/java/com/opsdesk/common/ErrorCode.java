package com.opsdesk.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码（规格基线 §20.2）—— 全项目唯一错误码定义处。
 *
 * <p>⚠️ 表里的 HTTP 状态码是**权威值**，必须与业务 code 一起返回（§20.2 约定 HTTP 与 code 对齐）。
 * 新增错误码前先改规格基线 §20.2，不要只改代码。
 */
@Getter
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, HttpStatus.OK, "success"),

    /** 参数校验失败（含 @Valid 校验失败、请求体不可解析） */
    PARAM_INVALID(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),

    /** 未登录 / token 无效或过期 */
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "未登录或 token 已失效"),

    /** 已登录但无该 API 权限 */
    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "无操作权限"),

    /** 有 API 权限但超出数据范围（§8） */
    DATA_SCOPE_DENIED(40301, HttpStatus.FORBIDDEN, "数据范围越权"),

    /** 资源不存在 */
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),

    /** 状态冲突：非法状态流转 / 乐观锁并发冲突（§7.4） */
    CONFLICT(40900, HttpStatus.CONFLICT, "状态冲突"),

    /** 请求过于频繁（如 AI 接口限流 20 次/分） */
    TOO_MANY_REQUESTS(42900, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    /** 系统错误（兜底） */
    SYSTEM_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "系统错误"),

    /** 依赖服务不可用（AI / MQ） */
    SERVICE_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE, "依赖服务不可用");

    /** 业务码 */
    private final int code;

    /** 对应的 HTTP 状态码 */
    private final HttpStatus httpStatus;

    /** 默认提示信息 */
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
