package ai.neargo.opskit.commons.tenancy;

import java.util.regex.Pattern;

final class Ids {
    /** 与 04 §一 一致：列宽 32，只允许小写字母、数字、下划线与连字符。 */
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,32}");

    private Ids() {
    }

    static void requireValid(String value, String name) {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + VALID.pattern() + ": " + value);
        }
    }
}
