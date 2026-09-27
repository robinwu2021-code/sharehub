package ai.neargo.opskit.process.api.statemachine;

import ai.neargo.opskit.commons.error.FieldError;
import java.util.List;

/** 定义不合法：构造状态机时即失败（「启动即失败」，需求 SM-08），并带上全部错误。 */
public class GraphDefinitionException extends RuntimeException {

    private final List<FieldError> errors;

    public GraphDefinitionException(String code, List<FieldError> errors) {
        super("invalid state machine " + code + ": " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
