package ai.neargo.opskit.commons.error;

/** 错误键 + HTTP 状态。键形如 {@code opskit.process.illegal_transition}，文案按语种在前端包里。 */
public record ErrorKey(String key, int httpStatus) {
    public ErrorKey {
        if (key == null || !key.startsWith("opskit.")) {
            throw new IllegalArgumentException("error key must start with 'opskit.': " + key);
        }
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("error http status must be 4xx/5xx: " + httpStatus);
        }
    }
}
