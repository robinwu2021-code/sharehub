package ai.neargo.opskit.commons.error;

import java.util.List;
import java.util.Map;

/** 业务可预期的失败。控制器层把它映射为 06 §二 的错误体；未预期的异常一律 500。 */
public class OpsKitException extends RuntimeException {

    private final ErrorKey errorKey;
    private final Map<String, Object> args;
    private final List<FieldError> fieldErrors;

    public OpsKitException(ErrorKey errorKey, String message) {
        this(errorKey, message, Map.of(), List.of());
    }

    public OpsKitException(ErrorKey errorKey, String message, Map<String, Object> args, List<FieldError> fieldErrors) {
        super(message);
        this.errorKey = errorKey;
        this.args = Map.copyOf(args);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorKey errorKey() {
        return errorKey;
    }

    public Map<String, Object> args() {
        return args;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
