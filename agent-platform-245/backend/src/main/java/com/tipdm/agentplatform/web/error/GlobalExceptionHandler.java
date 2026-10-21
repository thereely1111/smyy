package com.tipdm.agentplatform.web.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(ApiException ex) {
        return jsonError(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("请求参数不合法");
        return jsonError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体格式不合法");
    }

    /**
     * 必填查询参数缺失（如 {@code GET /x?path=} 少传 path）。
     *
     * <p>原先没有这个处理器，会落到兜底 500，把"调用方传错参数"显示成"服务端故障"，
     * 既误导前端也让排障记录失真。</p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "缺少必填参数 " + ex.getParameterName());
    }

    /**
     * 方法级参数校验失败（{@code @Validated} + {@code @RequestParam/@PathVariable} 约束）。
     * 原先同样落到兜底 500。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(
            ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse("请求参数不合法");
        return jsonError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleConflict(DataIntegrityViolationException ex) {
        // 必须落日志：对外文案是「如名称已存在」的猜测式提示，真实原因（哪列非空/哪个唯一键）
        // 只在最内层 cause 里，不记下来每次都要靠猜（2026-10-10 model 非空冲突实证）
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        return jsonError(HttpStatus.CONFLICT, "CONFLICT", "数据冲突（如名称已存在）");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "上传文件过大");
    }

    /**
     * 路径/查询参数类型不符（最常见是 `{id}` 传了非 UUID 值）。
     * 不单独处理同样会被兜成 500，掩盖「调用方传错参数」这一事实。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "参数 " + ex.getName() + " 格式不合法: " + ex.getValue());
    }

    /**
     * 请求未匹配到任何 Controller 时，Spring 会交给默认静态资源处理器兜底并抛出
     * {@link NoResourceFoundException}。此处必须单独处理：否则会被下面的
     * {@code Exception} 分支兜住，把「前端把接口路径写错了」伪装成
     * 500 INTERNAL_ERROR，并打印一整段无意义的堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("接口不存在: {} {}", request.getMethod(), request.getRequestURI());
        return jsonError(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在: " + request.getRequestURI());
    }

    /**
     * 异步请求超时（典型场景是 AG-UI SSE 连接达到 {@code agentscope.agui.sse-timeout}）。
     * 这是 SSE/长连接的正常生命周期结束事件，而不是服务端错误：客户端连接已超时断开，
     * 再向响应体写入 JSON 错误毫无意义（甚至会在 async dispatch 中二次抛错）。
     * 因此这里仅以 debug 记录，并返回 null 表示不写任何响应体。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletRequest request) {
        log.debug("异步请求超时（SSE 连接结束）: {} {}", request.getMethod(), request.getRequestURI());
        return null;
    }

    /**
     * 异步请求底层连接不可用（典型场景：SSE/长连接的客户端侧断开——页面刷新、窗口关闭、
     * 代理切断，服务端在下一次写（心跳/事件推送）时才发现管道破裂，Caused by 通常是
     * {@code IOException: Broken pipe}）。这是 SSE 的正常生命周期事件而非服务端错误：
     * 客户端已不可达，写任何错误响应都无意义。NotifyStreamRegistry 会在 send 失败时
     * 丢弃 emitter，前端 hook 会自动重连，兜底轮询覆盖丢失信号——此处仅 debug 记录，
     * 避免每次断连都打一整段 ERROR 堆栈（2026-09-14 实证：几分钟内刷屏十余次）。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncNotUsable(
            AsyncRequestNotUsableException ex, HttpServletRequest request) {
        Throwable cause = ex.getCause();
        log.debug("异步连接客户端断开: {} {} ({})", request.getMethod(), request.getRequestURI(),
                cause == null ? ex.getMessage() : cause.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception ex) {
        log.error("Unhandled exception", ex);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误");
    }

    /**
     * 构造 JSON 错误响应，显式设置 Content-Type 为 application/json。
     * <p>当异常来自声明了 {@code produces = TEXT_EVENT_STREAM_VALUE} 的 SSE 端点时，
     * Spring 内容协商会继承该约束，导致异常处理器返回 JSON 时触发
     * {@code HttpMediaTypeNotAcceptableException}。显式指定 Content-Type 可绕过此问题。
     */
    private ResponseEntity<Map<String, String>> jsonError(HttpStatus status, String code, String message) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agent-platform\"");
        }
        return response.body(Map.of("code", code, "message", message));
    }
}
