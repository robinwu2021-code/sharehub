package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.model.ActionView;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.query.SlaMetric;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.BulkReassignRequest;
import ai.neargo.opskit.ticket.api.model.BulkReassignResult;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import ai.neargo.opskit.ticket.api.model.CreateTicketResult;
import ai.neargo.opskit.ticket.api.model.TicketDetail;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.api.model.UpdateTicketRequest;
import ai.neargo.opskit.ticket.core.app.TicketAppService;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
import ai.neargo.opskit.svc.api.Present.Named;
import ai.neargo.opskit.svc.api.Present.People;
import ai.neargo.opskit.svc.api.Present.Person;
import ai.neargo.opskit.svc.api.Present.Subject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工单与工单类型（21/06 §五 · §七 TicketRow / TicketView）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class TicketController {

    private final TicketAppService tickets;
    private final TicketDefinitionAppService definitions;
    private final ProcessClient processes;
    private final DirectoryAppService directory;
    private final MemberAttrAppService attrs;

    public TicketController(TicketAppService tickets, TicketDefinitionAppService definitions, ProcessClient processes, DirectoryAppService directory,
            MemberAttrAppService attrs) {
        this.tickets = tickets;
        this.definitions = definitions;
        this.processes = processes;
        this.directory = directory;
        this.attrs = attrs;
    }

    // ── 出参形状 ────────────────────────────────────────────

    public record Priority(String code, String name, Map<String, String> names, int ordinal) {
    }

    public record Assignee(String ref, String displayName, Map<String, Map<String, Object>> attrs) {
    }

    public record Sla(String state, long startedAtMs, long dueAtMs, long pausedMs) {
    }

    public record Action(String code, String name, String toCategory, Requires requires) {
    }

    public record Requires(Set<String> fields, boolean comment) {
    }

    /** TicketRow（21/06 §七）。 */
    public record Row(String ticketNo, Named type, String title, Subject subject, Priority priority, Named status, String statusCategory, Assignee assignee,
            Person requester, String slaState, Long slaNextDueAtMs, Sla sla, boolean escalated, long createdAtMs, long updatedAtMs,
            Map<String, Map<String, Object>> fields, List<Action> actions) {
    }

    public record Metric(String code, String name, String state, Long startedAtMs, Long dueAtMs, long pausedMs) {
    }

    public record Participant(Named role, Person member) {
    }

    public record FieldItem(String code, String label, Map<String, Object> value) {
    }

    public record Dedupe(String key, int attachedCount) {
    }

    /** TicketView（21/06 §七）= TicketRow 全部 + 详情字段。 */
    public record View(String ticketNo, Named type, String title, Subject subject, Priority priority, Named status, String statusCategory,
            Assignee assignee, Person requester, String slaState, Long slaNextDueAtMs, Sla sla, boolean escalated, long createdAtMs, long updatedAtMs,
            Map<String, Map<String, Object>> fields, List<Action> actions, String description, List<FieldItem> fieldList, List<Participant> participants,
            List<Metric> slaMetrics, Dedupe dedupe, Named resolution, String mergedInto, Long closedAtMs, boolean open, Boolean attached) {
    }

    public record TypeOption(String code, String name, Map<String, String> names, boolean enabled) {
    }

    public record TypeField(String code, String name, Map<String, String> names, String type, boolean required, boolean onCreate, boolean filterable,
            List<TypeOption> options, boolean multiple, String refType) {
    }

    public record TypeDto(String code, String name, Map<String, String> names, String processCode, List<TypeField> fields, List<TypeOption> priorityOptions,
            List<TypeOption> resolutionOptions, String numberPrefix, String assignment, boolean enabled, boolean inherited, String version) {
    }

    // ── 入参 ────────────────────────────────────────────────

    public record CreateBody(String typeCode, String title, String description, Map<String, Object> fields, String priority, String requester,
            Set<String> scopes, String dedupeKey, SubjectBody subject) {
    }

    public record SubjectBody(String type, String ref, Map<String, Object> display) {
    }

    public record TransitionBody(String comment, String resolution) {
    }

    public record AssignBody(String to, String reason) {
    }

    public record ReasonBody(String reason) {
    }

    public record EscalateBody(String to, String reason) {
    }

    public record MergeBody(String into, String reason) {
    }

    public record WatchersBody(List<String> members) {
    }

    public record ExternalBody(String to) {
    }

    // ── 工单类型 ────────────────────────────────────────────

    @GetMapping("/ticket-types")
    public List<TypeDto> types(Actor actor, HttpServletRequest req) {
        String locale = Present.locale(req);
        return definitions.listTypes(actor.tenant()).stream().map(r -> type(actor.tenant(), r, locale)).toList();
    }

    @GetMapping("/ticket-types/{code}")
    public TypeDto type(Actor actor, @PathVariable("code") String code, HttpServletRequest req) {
        return definitions.resolveType(actor.tenant(), code).map(r -> type(actor.tenant(), r, Present.locale(req)))
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "ticket type not found"));
    }

    // ── 工单 ────────────────────────────────────────────────

    /** 建单；去重命中返回 200 + attached=true（返回的是既有单）。 */
    @PostMapping("/tickets")
    public View create(Actor actor, @RequestBody CreateBody b, HttpServletRequest req) {
        CreateTicketResult r = tickets.create(actor, new CreateTicketRequest(b.typeCode(), b.title(), b.description(), b.fields(), b.priority(), b.requester(),
                b.scopes(), b.dedupeKey(), b.subject() == null ? null : new SubjectRef(b.subject().type(), b.subject().ref(), b.subject().display())));
        return view(actor, tickets.detail(actor.isAppItself() ? actor : actorOrApp(actor, r), r.ticket().ticketNo()), Present.locale(req), r.attached());
    }

    @GetMapping("/tickets/{no}")
    public View get(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        return view(actor, tickets.detail(actor, no), Present.locale(req), null);
    }

    @PatchMapping("/tickets/{no}")
    public View update(Actor actor, @PathVariable("no") String no, @RequestBody UpdateTicketRequest b, HttpServletRequest req) {
        tickets.update(actor, no, b);
        return get(actor, no, req);
    }

    @PostMapping("/tickets/{no}/transitions/{code}")
    public View transition(Actor actor, @PathVariable("no") String no, @PathVariable("code") String code, @RequestBody(required = false) TransitionBody b,
            HttpServletRequest req) {
        tickets.transition(actor, no, code, b == null ? null : b.comment(), b == null ? null : b.resolution());
        return get(actor, no, req);
    }

    @PostMapping("/tickets/{no}/claim")
    public View claim(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        tickets.claim(actor, no);
        return get(actor, no, req);
    }

    @PostMapping("/tickets/{no}/assign")
    public View assign(Actor actor, @PathVariable("no") String no, @RequestBody AssignBody b, HttpServletRequest req) {
        tickets.assign(actor, no, b.to(), b.reason());
        return get(actor, no, req);
    }

    /** 退回抢单池（显式端点，不用 to: null）。 */
    @PostMapping("/tickets/{no}/pool")
    public View pool(Actor actor, @PathVariable("no") String no, @RequestBody ReasonBody b, HttpServletRequest req) {
        tickets.pool(actor, no, b.reason());
        return get(actor, no, req);
    }

    @PostMapping("/tickets/{no}/escalate")
    public View escalate(Actor actor, @PathVariable("no") String no, @RequestBody EscalateBody b, HttpServletRequest req) {
        tickets.escalate(actor, no, b.to(), b.reason());
        return get(actor, no, req);
    }

    @PostMapping("/tickets/{no}/merge")
    public View merge(Actor actor, @PathVariable("no") String no, @RequestBody MergeBody b, HttpServletRequest req) {
        tickets.merge(actor, no, b.into(), b.reason());
        return get(actor, no, req);
    }

    @PutMapping("/tickets/{no}/watchers")
    public View watchers(Actor actor, @PathVariable("no") String no, @RequestBody WatchersBody b, HttpServletRequest req) {
        tickets.setWatchers(actor, no, b.members());
        return get(actor, no, req);
    }

    /** 批量改派：{ticketNos | from} 二选一 + to + reason；逐单结果，单张失败不回滚其余。toGroup 暂不支持。 */
    @PostMapping("/tickets/reassign")
    public BulkReassignResult reassign(Actor actor, @RequestBody BulkReassignRequest b) {
        return tickets.bulkReassign(actor, b);
    }

    /** 外部指定派单的回填（仅应用）。 */
    @PostMapping("/tickets/{no}/assignment")
    public View externalAssignment(Actor actor, @PathVariable("no") String no, @RequestBody ExternalBody b, HttpServletRequest req) {
        tickets.externalAssignment(actor, no, b.to());
        return get(actor, no, req);
    }

    // ── 呈现 ────────────────────────────────────────────────

    /** 去重命中时操作人未必看得见既有单（不是参与者）：仍把那张单回给他 —— 他刚报的就是这件事。 */
    private static Actor actorOrApp(Actor actor, CreateTicketResult r) {
        return r.attached() ? new Actor(actor.tenant(), Optional.empty(), Optional.of("opskit-ticket"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of())
                : actor;
    }

    private View view(Actor actor, TicketDetail d, String locale, Boolean attached) {
        TenantContext t = actor.tenant();
        TicketView v = d.ticket();
        TicketDefinitionAppService.ResolvedType type = definitions.resolveType(t, v.typeCode()).orElse(null);
        TicketTypeDef def = type == null ? null : type.def();
        List<String> principals = new ArrayList<>();
        principals.add(v.assignee());
        principals.add(v.requester());
        d.participants().values().forEach(principals::addAll);
        collectMembers(def, v.fields(), principals);
        People people = new People(directory, t, principals);
        Map<String, Map<String, Object>> all = fields(t, def, v.fields(), people, locale, false);
        List<FieldItem> list = new ArrayList<>();
        if (def != null) {
            for (FieldDef f : def.fields()) {
                if (all.containsKey(f.code())) {
                    list.add(new FieldItem(f.code(), Present.text(f.name(), locale), all.get(f.code())));
                }
            }
        }
        List<Participant> participants = new ArrayList<>();
        d.participants().forEach((role, set) -> set.forEach(p -> participants.add(new Participant(new Named(role, role, Map.of()), people.of(p)))));
        return new View(v.ticketNo(), typeName(def, v.typeCode(), locale), v.title(), subject(v.subject()), priority(t, def, v, locale),
                status(t, def, v.status(), locale), v.statusCategory(), assignee(t, v.assignee(), people, locale), people.of(v.requester()), v.slaState(),
                v.slaNextDueAtMs(), sla(v), v.escalated(), v.createdAtMs(), v.updatedAtMs(), fields(t, def, v.fields(), people, locale, true),
                actions(d.actions(), locale), v.description(), list, participants, metrics(d.slaMetrics(), locale),
                v.dedupeKey() == null ? null : new Dedupe(v.dedupeKey(), d.dedupeHits()), resolution(t, def, v.resolution(), locale), v.mergedInto(),
                v.closedAtMs(), v.open(), attached);
    }

    private static void collectMembers(TicketTypeDef def, Map<String, Object> values, List<String> principals) {
        if (def == null) {
            return;
        }
        for (FieldDef f : def.fields()) {
            Object v = values.get(f.code());
            if (f.type() == FieldType.MEMBER && v instanceof String s) {
                principals.add(s);
            } else if (f.type() == FieldType.MEMBER && v instanceof Collection<?> c) {
                c.forEach(x -> principals.add(String.valueOf(x)));
            }
        }
    }

    /** 字段值一律 {value, name?}（21/06 §七）：选项 / 成员 / 组带显示名。{@code filterableOnly} = 列表只给可筛选字段。 */
    private Map<String, Map<String, Object>> fields(TenantContext t, TicketTypeDef def, Map<String, Object> values, People people, String locale,
            boolean filterableOnly) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (def == null) {
            values.forEach((k, v) -> out.put(k, Map.of("value", v)));
            return out;
        }
        for (FieldDef f : def.fields()) {
            Object v = values.get(f.code());
            if (v == null || filterableOnly && !f.filterable()) {
                continue;
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("value", v);
            String name = switch (f.type()) {
                case SELECT -> optionName(definitions.optionsOf(t, f.options()), (String) v, locale);
                case MULTI_SELECT -> String.join(", ", ((Collection<?>) v).stream().map(x -> optionName(definitions.optionsOf(t, f.options()), String.valueOf(x),
                        locale)).toList());
                case MEMBER -> v instanceof String s ? Optional.ofNullable(people.of(s)).map(Person::displayName).orElse(null) : null;
                case GROUP -> directory.findGroup(t, (String) v).filter(g -> !g.name().isEmpty())
                        .map(g -> new LocalizedText(g.name()).resolve(locale, "en")).orElse(null);
                default -> null;
            };
            if (name != null) {
                cell.put("name", name);
            }
            out.put(f.code(), cell);
        }
        return out;
    }

    private static String optionName(List<OptionDef> options, String code, String locale) {
        return options.stream().filter(o -> o.code().equals(code)).findFirst().map(o -> Present.text(o.name(), locale)).orElse(code);
    }

    private Priority priority(TenantContext t, TicketTypeDef def, TicketView v, String locale) {
        if (v.priority() == null) {
            return null;
        }
        OptionDef o = def == null ? null
                : definitions.optionsOf(t, def.priorityOptions()).stream().filter(x -> x.code().equals(v.priority())).findFirst().orElse(null);
        return new Priority(v.priority(), o == null ? v.priority() : Present.text(o.name(), locale), o == null || o.name() == null ? Map.of()
                : o.name().values(), v.priorityOrd() == null ? 0 : v.priorityOrd());
    }

    private Named resolution(TenantContext t, TicketTypeDef def, String code, String locale) {
        if (code == null) {
            return null;
        }
        OptionDef o = def == null ? null : definitions.optionsOf(t, def.resolutionOptions()).stream().filter(x -> x.code().equals(code)).findFirst().orElse(null);
        return o == null ? new Named(code, code, Map.of()) : Present.named(code, o.name(), locale);
    }

    private Named status(TenantContext t, TicketTypeDef def, String code, String locale) {
        if (def == null || "-".equals(code)) {
            return new Named(code, code, Map.of());
        }
        Optional<ProcessDef> p = processes.processDef(t, def.processCode());
        return p.flatMap(pd -> pd.statuses().stream().filter(s -> s.code().equals(code)).findFirst()).map(StatusDef::name)
                .map(n -> Present.named(code, n, locale)).orElse(new Named(code, code, Map.of()));
    }

    private Assignee assignee(TenantContext t, String principal, People people, String locale) {
        if (principal == null) {
            return null;
        }
        Person p = people.of(principal);
        Map<String, Map<String, Object>> a = directory.findMember(t, p.ref()).map(m -> attrs.render(t, m.attrs(), locale)).orElse(Map.of());
        return new Assignee(p.ref(), p.displayName(), a);
    }

    private static Sla sla(TicketView v) {
        return v.slaNextDueAtMs() == null || v.slaStartedAtMs() == null ? null : new Sla(v.slaState(), v.slaStartedAtMs(), v.slaNextDueAtMs(), v.slaPausedMs());
    }

    private static List<Action> actions(List<ActionView> as, String locale) {
        return as.stream().map(a -> new Action(a.code(), a.name().isEmpty() ? a.code() : new LocalizedText(a.name()).resolve(locale, "en"),
                a.toCategory().name(), new Requires(a.requiresFields(), a.requiresComment()))).toList();
    }

    private static List<Metric> metrics(List<SlaMetric> ms, String locale) {
        return ms.stream().map(m -> new Metric(m.metricCode(), Present.text(m.name(), locale) == null ? m.metricCode() : Present.text(m.name(), locale),
                m.state(), m.startedAtMs(), m.dueAtMs(), m.pausedMs())).toList();
    }

    private static Subject subject(SubjectRef s) {
        return Present.subject(s);
    }

    private static Named typeName(TicketTypeDef def, String code, String locale) {
        return def == null ? new Named(code, code, Map.of()) : Present.named(code, def.name(), locale);
    }

    private TypeDto type(TenantContext t, TicketDefinitionAppService.ResolvedType r, String locale) {
        TicketTypeDef d = r.def();
        return new TypeDto(d.code(), Present.text(d.name(), locale), d.name() == null ? Map.of() : d.name().values(), d.processCode(),
                d.fields().stream().map(f -> new TypeField(f.code(), Present.text(f.name(), locale), f.name() == null ? Map.of() : f.name().values(),
                        f.type().name(), f.required(), f.onCreate(), f.filterable(), f.options() == null ? null : options(definitions.optionsOf(t, f.options()),
                        locale), f.multiple(), f.refType())).toList(),
                options(definitions.optionsOf(t, d.priorityOptions()), locale), options(definitions.optionsOf(t, d.resolutionOptions()), locale),
                d.numberPrefix(), d.assignment().strategy().name(), r.enabled(), r.inherited(), r.version());
    }

    private static List<TypeOption> options(List<OptionDef> os, String locale) {
        return os.stream().map(o -> new TypeOption(o.code(), Present.text(o.name(), locale), o.name() == null ? Map.of() : o.name().values(), o.enabled()))
                .toList();
    }
}
