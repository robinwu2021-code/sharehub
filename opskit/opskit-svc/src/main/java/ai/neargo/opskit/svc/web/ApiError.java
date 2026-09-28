package ai.neargo.opskit.svc.web;

import java.util.List;
import java.util.Map;

/** 统一错误体（21/06 §二）：{@code {error, message, args, traceId, errors?}}。没有逐字段错误时不输出 errors（全局 non_null）。 */
public record ApiError(String error, String message, Map<String, Object> args, String traceId, List<Field> errors) {

    public ApiError {
        errors = errors == null || errors.isEmpty() ? null : List.copyOf(errors);
    }

    public record Field(String path, String error) {
    }
}
