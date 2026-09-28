package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.platform.api.directory.MemberAttrDef;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员选项 · 成员属性定义 · 我是谁（21/06 §三）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class DirectoryController {

    private final DirectoryAppService directory;
    private final MemberAttrAppService attrs;

    public DirectoryController(DirectoryAppService directory, MemberAttrAppService attrs) {
        this.directory = directory;
        this.attrs = attrs;
    }

    public record MemberQuery(String keyword, String group, Integer page, Integer size) {
    }

    public record MemberRow(String memberId, String displayName, Map<String, Map<String, Object>> attrs) {
    }

    public record AttrValue(String value, String name, Map<String, String> names) {
    }

    public record AttrDef(String key, String name, Map<String, String> names, List<AttrValue> values) {
    }

    public record Me(String memberId, String app, Set<String> groups, Set<String> roles, Set<String> permissions, Set<String> externalPerms,
            Set<String> scopes) {
    }

    @PostMapping("/members/query")
    public Page<MemberRow> members(Actor actor, @RequestBody(required = false) MemberQuery q, HttpServletRequest req) {
        MemberQuery query = q == null ? new MemberQuery(null, null, null, null) : q;
        String locale = Present.locale(req);
        return directory.queryMembers(actor, query.keyword(), query.group(), PageRequest.of(query.page(), query.size()))
                .map(m -> new MemberRow(m.memberId(), m.displayName(), attrs.render(actor.tenant(), m.attrs(), locale)));
    }

    @GetMapping("/member-attributes")
    public List<AttrDef> memberAttributes(Actor actor, HttpServletRequest req) {
        String locale = Present.locale(req);
        return attrs.list(actor.tenant()).stream().map((MemberAttrDef d) -> new AttrDef(d.key(), pick(d.name(), locale, d.key()), d.name(),
                d.valueLabels().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> new AttrValue(e.getKey(), pick(e.getValue(), locale, e.getKey()), e.getValue())).toList())).toList();
    }

    /** 前端据此亮按钮；与服务端判权用的是同一个解析结果。 */
    @GetMapping("/me")
    public Me me(Actor actor) {
        return new Me(actor.member().map(m -> Present.memberId(m.key())).orElse(null), actor.app().orElse(null), actor.groups(), actor.roles(),
                actor.productPermissions(), actor.externalPerms(), actor.scopes());
    }

    private static String pick(Map<String, String> names, String locale, String fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        return names.getOrDefault(locale, names.getOrDefault("en", names.values().iterator().next()));
    }
}
