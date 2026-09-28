package ai.neargo.opskit.connector;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * OpsKit 返回非 2xx（或网络不通）。{@link #errorKey()} 是稳定的错误键（文案见 opskit/docs/error-keys.json），
 * 按它分支，不要按 message。
 */
public final class OpsKitCallException extends RuntimeException {

    public record FieldError(String path, String error) {
    }

    private final int status;
    private final String errorKey;
    private final List<FieldError> fieldErrors;
    private final String traceId;

    OpsKitCallException(int status, String errorKey, String message, List<FieldError> fieldErrors, String traceId, Throwable cause) {
        super(status + " " + errorKey + (message == null ? "" : ": " + message) + (traceId == null ? "" : " traceId=" + traceId), cause);
        this.status = status;
        this.errorKey = errorKey;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.traceId = traceId;
    }

    static OpsKitCallException from(int status, JsonNode body) {
        List<FieldError> errors = new ArrayList<>();
        body.path("errors").forEach(e -> errors.add(new FieldError(e.path("path").asString(), e.path("error").asString())));
        return new OpsKitCallException(status, body.path("error").asString("http." + status), body.path("message").asString(null), errors,
                body.path("traceId").asString(null), null);
    }

    /** 0 = 网络不通 / 超时。 */
    public int status() {
        return status;
    }

    public String errorKey() {
        return errorKey;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public String traceId() {
        return traceId;
    }

    /**
     * 同一幂等键原样重试是否可能成功：网络、5xx、429、仍在处理（in_progress）、并发改动（state_changed，读最新后再做）。
     * 4xx 业务错误（校验、无权限、同键不同内容）重试也不会变。
     */
    public boolean retryable() {
        return status == 0 || status >= 500 || status == 429 || "opskit.idempotency.in_progress".equals(errorKey)
                || "opskit.state_changed".equals(errorKey);
    }
}
