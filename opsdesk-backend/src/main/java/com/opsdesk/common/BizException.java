package com.opsdesk.common;

/**
 * 业务异常（规格基线 §20.2）
 *
 * <p>凡是「可预期的业务失败」一律抛本异常，由 {@link GlobalExceptionHandler} 统一转成
 * {@code Result} + 对应 HTTP 状态码。**不要**在 Controller 里 try/catch 后手写错误响应。
 *
 * <p>用法：
 * <pre>{@code
 * throw new BizException(ErrorCode.CONFLICT, "当前状态不允许挂起");
 * throw new BizException(ErrorCode.DATA_SCOPE_DENIED);          // 用默认文案
 * }</pre>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对应规格基线 §20.2 的错误码 */
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 覆盖默认文案，用于把更具体的业务原因暴露给前端 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // ==================== 常用快捷构造 ====================

    /** 40400 资源不存在 */
    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    /** 40900 非法状态流转 / 并发冲突 */
    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }

    /** 40301 数据范围越权 */
    public static BizException dataScopeDenied(String message) {
        return new BizException(ErrorCode.DATA_SCOPE_DENIED, message);
    }

    /** 40300 无 API 权限 */
    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    /** 50300 依赖服务不可用（AI / MQ） */
    public static BizException serviceUnavailable(String message) {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, message);
    }
}
