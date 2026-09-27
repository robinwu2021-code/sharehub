package ai.neargo.opskit.commons.i18n;

import java.util.Map;
import java.util.Optional;

/** 定义里的多语种名称，如 {@code {"zh":"维修","en":"Repair","ar":"إصلاح"}}。 */
public record LocalizedText(Map<String, String> values) {

    public LocalizedText {
        values = Map.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("localized text needs at least one locale");
        }
    }

    public static LocalizedText of(String locale, String text) {
        return new LocalizedText(Map.of(locale, text));
    }

    /** 先取所请求的语种，再取回退语种，最后取任意一个 —— 名称永远不会显示成空。 */
    public String resolve(String locale, String fallbackLocale) {
        return Optional.ofNullable(values.get(locale))
                .or(() -> Optional.ofNullable(values.get(fallbackLocale)))
                .orElseGet(() -> values.values().iterator().next());
    }
}
