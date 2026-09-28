package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.api.model.SubjectRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出参的呈现规则（21/06 §二 · §七）：名称按 Accept-Language 选好给 {@code name}，全部语种给 {@code names}；
 * 成员引用给 {@code {ref, displayName}}（ref 是成员 ID，不带 member: 前缀），显示名从目录补，查不到为空。
 */
public final class Present {

    private static final Set<String> LOCALES = Set.of("zh", "en", "ar");

    private Present() {
    }

    public static String locale(HttpServletRequest req) {
        String lang = req.getLocale() == null ? "en" : req.getLocale().getLanguage().toLowerCase(Locale.ROOT);
        return LOCALES.contains(lang) ? lang : "en";
    }

    public record Named(String code, String name, Map<String, String> names) {
    }

    public record Person(String ref, String displayName) {
    }

    public record Subject(String type, String ref, Map<String, Object> display) {
    }

    public static Named named(String code, LocalizedText text, String locale) {
        return text == null ? new Named(code, code, Map.of()) : new Named(code, text.resolve(locale, "en"), text.values());
    }

    public static String text(LocalizedText text, String locale) {
        return text == null ? null : text.resolve(locale, "en");
    }

    /** display 至少有 title：业务没给就用业务编号。 */
    public static Subject subject(SubjectRef s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>(s.display());
        d.putIfAbsent("title", s.ref());
        return new Subject(s.type(), s.ref(), d);
    }

    public static String memberId(String principal) {
        return principal != null && principal.startsWith("member:") ? principal.substring(7) : principal;
    }

    /** 一页里的成员引用一次性补显示名。 */
    public static final class People {
        private final Map<String, String> names;

        public People(DirectoryAppService directory, TenantContext t, Collection<String> principals) {
            List<String> ids = principals.stream().filter(Objects::nonNull).filter(p -> p.startsWith("member:")).map(Present::memberId)
                    .distinct().toList();
            this.names = ids.isEmpty() ? Map.of() : directory.displayNames(t, ids);
        }

        public Person of(String principal) {
            if (principal == null) {
                return null;
            }
            String id = memberId(principal);
            return new Person(id, names.get(id));
        }

        public static Set<String> none() {
            return Set.of();
        }
    }

    public static <T> List<T> nonNull(Collection<T> c) {
        return c.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }
}
