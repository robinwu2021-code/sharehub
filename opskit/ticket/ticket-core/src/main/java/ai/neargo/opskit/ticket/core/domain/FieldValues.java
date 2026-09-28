package ai.neargo.opskit.ticket.core.domain;

import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 字段值的校验与规范化（21/07 O3-2）：11 种类型各自的合法形状、必填 / 建单可填、选项存在且启用；
 * 顺带产出可筛选字段的索引行、范围标签与去重键。纯函数。
 */
public final class FieldValues {

    public enum Mode { CREATE, UPDATE }

    /** 索引表一行（多值字段一值一行）。 */
    public record IndexValue(String field, String vStr, BigDecimal vNum, Long vTime) {
    }

    /** @param values 规范化后的全部字段值（UPDATE 时已并入原值） */
    public record Result(Map<String, Object> values, List<IndexValue> index, Set<String> scopes, String dedupeKey) {
    }

    public static final int TEXT_MAX = 255;
    public static final int LONG_TEXT_MAX = 20_000;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private FieldValues() {
    }

    /**
     * @param options SELECT / MULTI_SELECT 字段的可选项（已解析选项集引用）
     * @param current UPDATE 时的原值；CREATE 时传空 Map
     * @return 通过校验的结果；不通过时 {@code errors} 非空、返回 null
     */
    public static Result normalize(TicketTypeDef type, Function<FieldDef, List<OptionDef>> options, Map<String, Object> input, Mode mode,
            Map<String, Object> current, List<FieldError> errors) {
        Map<String, Object> out = new LinkedHashMap<>(mode == Mode.UPDATE ? current : Map.of());
        for (String code : input.keySet()) {
            if (type.field(code).isEmpty()) {
                errors.add(new FieldError("fields." + code, "opskit.ticket.unknown_field"));
            }
        }
        for (FieldDef f : type.fields()) {
            String path = "fields." + f.code();
            boolean given = input.containsKey(f.code());
            Object raw = input.get(f.code());
            if (mode == Mode.CREATE && given && !f.onCreate() && !empty(raw)) {
                errors.add(new FieldError(path, "opskit.ticket.not_on_create"));
                continue;
            }
            if (!given) {
                if (mode == Mode.CREATE && f.required()) {
                    errors.add(new FieldError(path, "opskit.validation.required"));
                }
                continue;
            }
            if (empty(raw)) {
                if (f.required()) {
                    errors.add(new FieldError(path, "opskit.validation.required"));
                } else {
                    out.remove(f.code());
                }
                continue;
            }
            Object v = value(f, raw, options.apply(f), current.get(f.code()), path, errors);
            if (v != null) {
                out.put(f.code(), v);
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return new Result(out, index(type, out), scopes(type, out), dedupeKey(type, out));
    }

    /** 单个值 → 规范形状；不合法记错误并返回 null。 */
    @SuppressWarnings("unchecked")
    static Object value(FieldDef f, Object raw, List<OptionDef> options, Object previous, String path, List<FieldError> e) {
        switch (f.type()) {
            case TEXT, LONG_TEXT -> {
                if (!(raw instanceof String s)) {
                    return bad(e, path, "opskit.ticket.expect_text");
                }
                if (s.length() > (f.type() == FieldType.TEXT ? TEXT_MAX : LONG_TEXT_MAX)) {
                    return bad(e, path, "opskit.validation.too_long");
                }
                return s;
            }
            case NUMBER -> {
                BigDecimal n = number(raw);
                return n == null ? bad(e, path, "opskit.ticket.expect_number") : n;
            }
            case MONEY -> {
                if (!(raw instanceof Map<?, ?> m) || !(m.get("amount") instanceof Number amt) || amt instanceof Double || amt instanceof Float
                        || !(m.get("currency") instanceof String cur) || !CURRENCY.matcher(cur).matches()) {
                    return bad(e, path, "opskit.ticket.expect_money");
                }
                Map<String, Object> money = new LinkedHashMap<>();
                money.put("amount", amt.longValue());
                money.put("currency", cur);
                return money;
            }
            case DATETIME -> {
                if (!(raw instanceof Number n) || raw instanceof Double || raw instanceof Float) {
                    return bad(e, path, "opskit.ticket.expect_epoch_ms");
                }
                return n.longValue();
            }
            case SELECT -> {
                if (!(raw instanceof String s)) {
                    return bad(e, path, "opskit.ticket.expect_option");
                }
                return option(s, options, previous, path, e);
            }
            case MULTI_SELECT -> {
                if (!(raw instanceof Collection<?> c) || c.stream().anyMatch(x -> !(x instanceof String))) {
                    return bad(e, path, "opskit.ticket.expect_option_list");
                }
                Collection<Object> prev = previous instanceof Collection<?> pc ? (Collection<Object>) pc : List.of();
                List<String> codes = new ArrayList<>(new LinkedHashSet<>((Collection<String>) c));
                for (String s : codes) {
                    if (option(s, options, prev.contains(s) ? s : null, path, e) == null) {
                        return null;
                    }
                }
                return codes;
            }
            case MEMBER -> {
                if (f.multiple()) {
                    if (!(raw instanceof Collection<?> c) || c.isEmpty()) {
                        return bad(e, path, "opskit.ticket.expect_member_list");
                    }
                    List<String> ms = new ArrayList<>();
                    for (Object x : c) {
                        String m = member(x);
                        if (m == null) {
                            return bad(e, path, "opskit.ticket.expect_member");
                        }
                        ms.add(m);
                    }
                    return List.copyOf(new LinkedHashSet<>(ms));
                }
                String m = member(raw);
                return m == null ? bad(e, path, "opskit.ticket.expect_member") : m;
            }
            case GROUP -> {
                return raw instanceof String s && !s.isBlank() && !s.contains(":") ? s : bad(e, path, "opskit.ticket.expect_group");
            }
            case ATTACHMENT -> {
                if (!(raw instanceof Collection<?> c)) {
                    return bad(e, path, "opskit.ticket.expect_attachments");
                }
                List<Map<String, Object>> files = new ArrayList<>();
                for (Object x : c) {
                    if (!(x instanceof Map<?, ?> m) || !(m.get("id") instanceof String id) || id.isBlank() || !(m.get("name") instanceof String name)) {
                        return bad(e, path, "opskit.ticket.expect_attachments");
                    }
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", id);
                    a.put("name", name);
                    if (m.get("size") instanceof Number size) {
                        a.put("size", size.longValue());
                    }
                    if (m.get("contentType") instanceof String ct) {
                        a.put("contentType", ct);
                    }
                    files.add(a);
                }
                return files;
            }
            case REF -> {
                if (!(raw instanceof Map<?, ?> m) || !(m.get("type") instanceof String t) || t.isBlank() || !(m.get("ref") instanceof String r) || r.isBlank()) {
                    return bad(e, path, "opskit.ticket.expect_ref");
                }
                if (f.refType() != null && !f.refType().equals(t)) {
                    return bad(e, path, "opskit.ticket.ref_type_mismatch");
                }
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", t);
                ref.put("ref", r);
                if (m.get("display") instanceof Map<?, ?> d) {
                    ref.put("display", d);
                }
                return ref;
            }
        }
        throw new IllegalStateException("unhandled field type " + f.type());
    }

    /** 选项必须存在；新选的必须启用（原值是已停用的选项时保持不报错 —— 停用不追溯）。 */
    private static Object option(String code, List<OptionDef> options, Object previous, String path, List<FieldError> e) {
        OptionDef o = options.stream().filter(x -> x.code().equals(code)).findFirst().orElse(null);
        if (o == null) {
            return bad(e, path, "opskit.ticket.unknown_option");
        }
        if (!o.enabled() && !code.equals(previous)) {
            return bad(e, path, "opskit.ticket.option_disabled");
        }
        return code;
    }

    private static List<IndexValue> index(TicketTypeDef type, Map<String, Object> values) {
        List<IndexValue> out = new ArrayList<>();
        for (FieldDef f : type.fields()) {
            Object v = values.get(f.code());
            if (!f.filterable() || v == null) {
                continue;
            }
            switch (f.type()) {
                case TEXT, SELECT, GROUP -> out.add(new IndexValue(f.code(), truncate((String) v), null, null));
                case MEMBER -> strings(v).forEach(s -> out.add(new IndexValue(f.code(), s, null, null)));
                case MULTI_SELECT -> strings(v).forEach(s -> out.add(new IndexValue(f.code(), s, null, null)));
                case NUMBER -> out.add(new IndexValue(f.code(), null, (BigDecimal) v, null));
                case MONEY -> {
                    Map<?, ?> m = (Map<?, ?>) v;
                    out.add(new IndexValue(f.code(), (String) m.get("currency"), BigDecimal.valueOf((Long) m.get("amount")), null));
                }
                case DATETIME -> out.add(new IndexValue(f.code(), null, null, (Long) v));
                case REF -> {
                    Map<?, ?> m = (Map<?, ?>) v;
                    out.add(new IndexValue(f.code(), truncate(m.get("type") + ":" + m.get("ref")), null, null));
                }
                case LONG_TEXT, ATTACHMENT -> {
                }
            }
        }
        return out;
    }

    private static Set<String> scopes(TicketTypeDef type, Map<String, Object> values) {
        Set<String> out = new LinkedHashSet<>();
        for (TicketTypeDef.ScopeFrom s : type.scopeFrom()) {
            Object v = values.get(s.field());
            if (v == null) {
                continue;
            }
            FieldDef f = type.field(s.field()).orElseThrow();
            List<String> raw = f.type() == FieldType.REF ? List.of(String.valueOf(((Map<?, ?>) v).get("ref"))) : strings(v);
            raw.stream().map(x -> x.startsWith("member:") ? x.substring(7) : x).forEach(x -> out.add(truncate(s.dimension() + ":" + x, 128)));
        }
        return out;
    }

    /** 类型代号 + 各去重字段的值；缺任何一个 = 不去重。太长时用哈希（列宽 160）。 */
    private static String dedupeKey(TicketTypeDef type, Map<String, Object> values) {
        if (type.dedupe() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(type.code());
        for (String code : type.dedupe().fields()) {
            Object v = values.get(code);
            if (v == null) {
                return null;
            }
            parts.add(v instanceof Map<?, ?> m && m.containsKey("ref") ? m.get("type") + ":" + m.get("ref") : String.valueOf(v));
        }
        String key = String.join("|", parts);
        return key.length() <= 160 ? key : type.code() + "|#" + sha256(key);
    }

    private static List<String> strings(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v));
    }

    private static String member(Object x) {
        if (!(x instanceof String s) || s.isBlank()) {
            return null;
        }
        if (s.startsWith("member:")) {
            return s.length() > 7 ? s : null;
        }
        return s.contains(":") ? null : "member:" + s;
    }

    private static BigDecimal number(Object raw) {
        if (raw instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null; // NaN / Infinity
            }
        }
        return null;
    }

    private static boolean empty(Object v) {
        return v == null || v instanceof String s && s.isEmpty() || v instanceof Collection<?> c && c.isEmpty();
    }

    private static Object bad(List<FieldError> e, String path, String error) {
        e.add(new FieldError(path, error));
        return null;
    }

    private static String truncate(String s) {
        return truncate(s, 255);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
