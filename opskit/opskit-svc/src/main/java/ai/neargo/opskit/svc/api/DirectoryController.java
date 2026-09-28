package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupUpsert;
import ai.neargo.opskit.platform.api.directory.MemberAttrDef;
import ai.neargo.opskit.platform.api.directory.SyncBatch;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员选项 · 成员属性定义 · 我是谁 · 目录同步 · 组 / 属性定义维护（21/06 §三）。 */
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

    /**
     * 目录同步（语义见 {@link SyncBatch}：MERGE 只增改；REPLACE 对本批出现的组 / 成员以本批为准）。只认应用凭证 ——
     * 这是宿主把它的人事数据灌进来的通道，成员令牌（哪怕持 directory:manage）不该走它。整批一个事务。
     */
    @PostMapping("/directory/sync")
    public DirectoryAppService.SyncResult sync(Actor actor, @RequestBody SyncBatch batch) {
        if (!actor.isAppItself()) {
            throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "directory sync requires an application credential");
        }
        return directory.sync(actor, batch == null ? new SyncBatch(null, null, null, null, null) : batch);
    }

    public record GroupBody(Map<String, String> name, GroupKind kind, Integer claimTimeoutSec, String orderBy) {
    }

    /** 建 / 改组（directory:manage）。TEAM = 团队（如代理公司）；QUEUE = 队列（任务进组内待领，可设领取超时）。 */
    @PutMapping("/groups/{code}")
    public void upsertGroup(Actor actor, @PathVariable("code") String code, @RequestBody GroupBody body) {
        if (body == null || body.kind() == null) {
            throw new OpsKitException(CommonErrors.VALIDATION, "kind is required", Map.of(),
                    List.of(new FieldError("kind", "opskit.validation.required")));
        }
        directory.upsertGroup(actor, new GroupUpsert(code, body.name(), body.kind(), body.claimTimeoutSec(), body.orderBy()));
    }

    public record AttrBody(Map<String, String> name, Map<String, Map<String, String>> valueLabels) {
    }

    /** 建 / 改成员属性定义（directory:manage），如 partyType：STAFF / AGENT 的三语显示名。 */
    @PutMapping("/member-attributes/{key}")
    public void upsertAttr(Actor actor, @PathVariable("key") String key, @RequestBody AttrBody body) {
        attrs.upsert(actor, actor.tenant().workspaceId(), new MemberAttrDef(key, body == null ? null : body.name(),
                body == null ? null : body.valueLabels()));
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
