package com.opsdesk.common;

import lombok.Data;

/**
 * 统一响应体（规格基线 §20.1）
 *
 * <pre>
 * { "code": 0, "message": "success", "data": {} }
 * </pre>
 *
 * <p>约定（§20.2）：<b>HTTP 状态码与业务 code 对齐</b>，不是「HTTP 恒 200」。
 * 也就是说失败时既要 body 里的 {@code code} 正确，HTTP status 也要是 400 / 403 / 404 / 409 / 429 / 500 / 503。
 * 该映射由 {@link ErrorCode#getHttpStatus()} 提供，由 {@code GlobalExceptionHandler} 落到 ResponseEntity 上。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 业务码，0 = 成功 */
    private int code;

    /** 提示信息，成功时固定为 "success" */
    private String message;

    /** 业务数据，失败时为 null 或字段级错误 Map（见 §20.2 参数校验失败示例） */
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ==================== 成功 ====================

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 用错误码兜底、但覆盖提示信息（用于把更具体的业务原因告诉前端） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 需要把字段级错误明细放进 data 时用（§20.2 参数校验失败） */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.getCode(), message, data);
    }

    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }
}
