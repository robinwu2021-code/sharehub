package ai.neargo.opskit.platform.api.directory;

import java.util.Map;

/**
 * 成员属性定义（21/07 O1-12）：让「AGENT」显示成「代理」由租户配置，而不是前端内置。
 *
 * @param key         属性键，如 {@code partyType}
 * @param name        属性名称的多语种，如 {@code {"zh":"身份"}}
 * @param valueLabels 属性值 → 多语种显示名，如 {@code {"AGENT":{"zh":"代理","en":"Agent"}}}
 */
public record MemberAttrDef(String key, Map<String, String> name, Map<String, Map<String, String>> valueLabels) {
    public MemberAttrDef {
        name = name == null ? Map.of() : Map.copyOf(name);
        valueLabels = valueLabels == null ? Map.of() : Map.copyOf(valueLabels);
    }
}
