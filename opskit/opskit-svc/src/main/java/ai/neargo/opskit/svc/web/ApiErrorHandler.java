package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.commons.error.OpsKitException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 异常 → 统一错误体。业务错误的 HTTP 码由错误键自带（409 冲突 / 403 规则拒绝 / 422 校验，21/06 §二）；
 * 未预期的异常一律 500 且只回追踪号，细节只进日志。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(OpsKitException.class)
    ResponseEntity<ApiError> business(OpsKitException e) {
        int status = e.errorKey().httpStatus();
        if (status >= 500) {
            log.error("业务异常返回 5xx error={}", e.errorKey().key(), e);
        }
        return ResponseEntity.status(status).body(new ApiError(e.errorKey().key(), e.getMessage(), e.args(), traceId(),
                e.fieldErrors().stream().map(f -> new ApiError.Field(f.path(), f.error())).toList()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("opskit.bad_request", "malformed request", Map.of(), traceId(), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(new ApiError("opskit.not_found", "no such endpoint", Map.of(), traceId(), List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(new ApiError("opskit.method_not_allowed", e.getMessage(), Map.of(), traceId(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("未预期异常，需排查 traceId={}", traceId(), e);
        return ResponseEntity.status(500).body(new ApiError("opskit.internal", "internal error", Map.of(), traceId(), List.of()));
    }

    private static String traceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
