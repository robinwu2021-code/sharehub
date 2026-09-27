package ai.neargo.opskit.commons.error;

/** 逐条校验错误：{@code path} 形如 {@code transitions[2].when}，{@code error} 为错误键。 */
public record FieldError(String path, String error) {
}
