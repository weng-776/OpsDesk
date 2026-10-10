package com.opsdesk.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理（规格基线 §20.2 / §23.7）
 *
 * <p>两条硬约定：
 * <ol>
 *   <li><b>HTTP 状态码与业务 code 对齐</b> —— 失败时 HTTP status 取
 *       {@link ErrorCode#getHttpStatus()}，不是「HTTP 恒 200」</li>
 *   <li>参数校验失败时，{@code data} 放**字段 → 错误信息**的 Map（§20.2 示例）</li>
 * </ol>
 *
 * <p>响应体结构见 {@link Result}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /** 业务异常：唯一「可预期失败」的出口 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("[业务异常] code={} message={}", ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getHttpStatus())
                .body(Result.fail(ec, ex.getMessage()));
    }

    // ==================== 参数校验 ====================

    /** @Valid 校验 @RequestBody 失败 → 40001 + 字段级明细 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[参数校验失败] {}", errors);
        return badRequest(errors);
    }

    /** 表单 / 查询参数绑定校验失败 → 40001 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Map<String, String>>> handleBind(BindException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[参数绑定失败] {}", errors);
        return badRequest(errors);
    }

    /** @Validated 校验方法参数（@RequestParam / @PathVariable）失败 → 40001 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            String path = cv.getPropertyPath() == null ? "param" : cv.getPropertyPath().toString();
            // 只取最后一段，避免 "listTickets.page" 这种长路径
            int dot = path.lastIndexOf('.');
            errors.putIfAbsent(dot >= 0 ? path.substring(dot + 1) : path, cv.getMessage());
        }
        log.warn("[参数校验失败] {}", errors);
        return badRequest(errors);
    }

    /** 请求体不是合法 JSON / 类型不匹配 → 40001 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("[请求体不可解析] {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, "请求体格式不正确"));
    }

    /** 缺少必填查询参数 → 40001 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String msg = "缺少必填参数：" + ex.getParameterName();
        log.warn("[{}]", msg);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    /** 参数类型不匹配（如 ?page=abc）→ 40001 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String msg = "参数类型不正确：" + ex.getName();
        log.warn("[{}]", msg);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    /**
     * 上传文件超过 multipart 上限 → 40001（D3-05）。
     *
     * <p>§8.9「单文件 ≤10MB」+ 错误码表「长度超限」归 {@code 40001}。
     * 该异常由容器在 <b>multipart 解析阶段</b>抛出，早于业务层校验，所以必须在这里兜住；
     * 否则会落到 {@link #handleUnexpected} 被误报成 {@code 50000} 系统错误。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        long max = ex.getMaxUploadSize();
        String msg = max > 0
                ? "上传文件过大，单文件不得超过 " + (max / 1024 / 1024) + "MB"
                : "上传文件过大";
        log.warn("[上传超限] maxUploadSize={} bytes", max);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    // ==================== 路由 ====================

    /** 路径不存在 → 40400 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception ex) {
        log.warn("[路径不存在] {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(Result.fail(ErrorCode.NOT_FOUND, "请求的接口不存在"));
    }

    /** 请求方法不支持 → 40001（§20.2 无 405 码，归入请求不合法） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String msg = "请求方法不支持：" + ex.getMethod();
        log.warn("[{}]", msg);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    // ==================== 兜底 ====================

    /**
     * 兜底：未预期异常 → 50000。
     * ⚠️ 这里必须打完整堆栈，否则线上问题无从排查；但**不要把堆栈返回给前端**。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("[系统异常] 未预期异常", ex);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    private ResponseEntity<Result<Map<String, String>>> badRequest(Map<String, String> errors) {
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Result.fail(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), errors));
    }
}
