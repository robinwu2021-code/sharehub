package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.Permission;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.domain.definition.ApprovalTemplateCompiler;
import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.api.model.query.ApprovalRow;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.InstanceQuery;
import ai.neargo.opskit.process.api.model.query.InstanceRow;
import ai.neargo.opskit.process.api.model.query.ProcessStats;
import ai.neargo.opskit.process.api.model.query.SubjectHistory;
import ai.neargo.opskit.process.core.app.ProcessHistoryService;
import ai.neargo.opskit.svc.api.Present.Named;
import ai.neargo.opskit.svc.api.Present.People;
import ai.neargo.opskit.svc.api.Present.Person;
import ai.neargo.opskit.svc.api.Present.Subject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 实例历史 · 实例查询 · 对账（按业务对象）· 流程统计（21/06 §四 · 21/07 O2-10）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class InstanceController {

    private final ProcessHistoryService history;
    private final DirectoryAppService directory;

    private final ai.neargo.opskit.process.client.ProcessClient processes;
    private final ai.neargo.opskit.process.core.app.ApprovalTemplateAppService templates;

    public InstanceController(ProcessHistoryService history, DirectoryAppService directory, ai.neargo.opskit.process.client.ProcessClient processes,
            ai.neargo.opskit.process.core.app.ApprovalTemplateAppService templates) {
        this.templates = templates;
        this.history = history;
        this.directory = directory;
        this.processes = processes;
    }

    public record Row(String instanceNo, Named process, String processVersion, Subject subject, Named status, String statusCategory, boolean open,
            Person createdBy, long createdAtMs, long updatedAtMs, Long closedAtMs) {
    }

    /**
     * @param actor     成员给 {ref, displayName}；应用 / 系统操作时为空，看 {@code actorText}（{@code app:x} / {@code system}）
     * @param entityKind INSTANCE / TASK / APPROVAL / SLA / GATE
     */
    public record Entry(String entityKind, String entityNo, String action, String actionName, String from, String fromName, String to, String toName,
            Integer seq, Person actor, String actorText, String reason, Map<String, Object> payload, long atMs) {
    }

    public record ApprovalBrief(String approvalNo, String status, int round, Person requestedBy, long requestedAtMs, Long finishedAtMs) {
    }

    public record SubjectItem(Row instance, List<ApprovalBrief> approvals, List<Entry> history) {
    }

    public record SubjectDto(String type, String ref, long total, boolean truncated, List<SubjectItem> instances) {
    }

    public record StatsQuery(String processCode, Long fromMs, Long toMs) {
    }

    public record StatsDto(Named process, long total, long open, Map<String, Long> byCategory, Long avgCycleMs, Map<String, Long> approvals) {
    }

    public record SubjectBody(String type, String ref, Map<String, Object> display) {
    }

    /** onBehalfOf 由 CallerResolver 从同一请求体解析，这里不重复声明。 */
    public record StartBody(String processCode, SubjectBody subject, Map<String, Object> fields, Map<String, List<String>> participants,
            java.util.Set<String> scopes, List<String> slaPolicies) {
    }

    /** approvalNo：发起后若停在审批门，带上当前审批单号（接入方据此在自己的业务页展示进度）。 */
    public record Started(String instanceNo, String processCode, String processVersion, String status, String statusCategory, boolean open,
            int seq, String approvalNo, long createdAtMs) {
    }

    /**
     * 发起实例（21/06 §实例）。
     * <ul>
     *   <li>应用凭证：任何流程；可带 onBehalfOf 代提交人发起（提交人成为 REQUESTER，自审禁止据此判）。</li>
     *   <li>成员令牌（运营台「发起审批」）：只能发起<b>审批模板</b>，需 {@code approval:submit}；填的字段按模板的发起表单校验。</li>
     * </ul>
     * 审批模板编译出的流程以「草稿」起步：这里发起后立即走 submit 进入审批（没有草稿编辑界面，停在草稿没有意义），同一请求内完成。
     */
    @PostMapping("/instances")
    public Started start(Actor actor, @RequestBody StartBody body) {
        if (body == null || body.processCode() == null || body.processCode().isBlank()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "processCode is required", Map.of(),
                    List.of(new FieldError("processCode", "opskit.validation.required")));
        }
        java.util.Optional<ApprovalTemplate> template = templates.get(actor.tenant(), body.processCode());
        boolean member = actor.app().isEmpty();
        if (member) {
            if (!actor.productPermissions().contains(Permission.APPROVAL_SUBMIT.code())) {
                throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "submitting approvals needs " + Permission.APPROVAL_SUBMIT.code());
            }
            if (template.isEmpty()) {
                throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "members can only start approval templates");
            }
        }
        Map<String, Object> fields = body.fields() == null ? Map.of() : body.fields();
        if (template.isPresent()) {
            List<FieldError> errors = ApprovalTemplateCompiler.validateSubmission(template.get(), fields);
            if (!errors.isEmpty()) {
                throw new OpsKitException(CommonErrors.VALIDATION, "invalid submission", Map.of("code", body.processCode()), errors);
            }
        }
        SubjectRef subject = body.subject() == null ? null : new SubjectRef(body.subject().type(), body.subject().ref(), body.subject().display());
        InstanceView v = processes.start(actor, new StartRequest(body.processCode(), subject, fields, body.participants(), body.scopes(), body.slaPolicies()));
        if (template.isPresent() && ApprovalTemplateCompiler.DRAFT.equals(v.status())) {
            v = processes.transition(actor, v.instanceNo(), "submit", new TransitionRequest(Map.of(), null));
        }
        return new Started(v.instanceNo(), v.processCode(), v.processVersion(), v.status(), v.statusCategory().name(), v.open(), v.seq(),
                history.pendingApprovalNo(actor.tenant(), v.instanceNo()).orElse(null), v.createdAtMs());
    }

    public record TransitionBody(Map<String, Object> fields, String comment) {
    }

    /**
     * 走一条迁移（21/06 §实例）。授权由流程定义的 who 判定（应用自身按系统调用放行），{@code requires} 要求的字段 / 说明缺了 → 422。
     * 应用凭证可带 onBehalfOf 以某人身份走。返回与发起同形（含迁移后若停在审批门的 approvalNo）。
     */
    @PostMapping("/instances/{no}/transitions/{code}")
    public Started transition(Actor actor, @PathVariable("no") String no, @PathVariable("code") String code,
            @RequestBody(required = false) TransitionBody body) {
        InstanceView v = processes.transition(actor, no, code, new TransitionRequest(body == null ? Map.of() : body.fields(),
                body == null ? null : body.comment()));
        return new Started(v.instanceNo(), v.processCode(), v.processVersion(), v.status(), v.statusCategory().name(), v.open(), v.seq(),
                history.pendingApprovalNo(actor.tenant(), v.instanceNo()).orElse(null), v.createdAtMs());
    }

    /** @param principals {@code member:…} / {@code group:…}；空列表 = 清空该角色 */
    public record ParticipantsBody(String role, List<String> principals) {
    }

    /** 整体替换某个参与者角色（仅应用自身；宿主业务里负责人变了时同步过来）。 */
    @PutMapping("/instances/{no}/participants")
    public ResponseEntity<Void> participants(Actor actor, @PathVariable("no") String no, @RequestBody ParticipantsBody body) {
        if (body == null || body.role() == null || body.role().isBlank()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "role is required", Map.of(), List.of(new FieldError("role", "opskit.validation.required")));
        }
        processes.replaceParticipants(actor, no, body.role(), body.principals() == null ? List.of() : body.principals());
        return ResponseEntity.noContent().build();
    }

    public record ScopesBody(List<String> tags) {
    }

    /** 整体替换范围标签（仅应用自身；如业务行换了片区）。 */
    @PutMapping("/instances/{no}/scopes")
    public ResponseEntity<Void> scopes(Actor actor, @PathVariable("no") String no, @RequestBody ScopesBody body) {
        processes.replaceScopes(actor, no, body == null || body.tags() == null ? List.of() : body.tags());
        return ResponseEntity.noContent().build();
    }

    /** 单个实例行（与 instances/query 的行同形）；看不见 404。 */
    @GetMapping("/instances/{no}")
    public Row instance(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        InstanceRow r = history.instance(actor, no);
        return row(r, new People(directory, actor.tenant(), java.util.Collections.singletonList(r.createdBy())), Present.locale(req));
    }

    @GetMapping("/instances/{no}/history")
    public List<Entry> history(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        List<HistoryEntry> hs = history.history(actor, no);
        People people = new People(directory, actor.tenant(), hs.stream().map(HistoryEntry::actor).toList());
        String locale = Present.locale(req);
        return hs.stream().map(h -> entry(h, people, locale)).toList();
    }

    @PostMapping("/instances/query")
    public Page<Row> query(Actor actor, @RequestBody(required = false) InstanceQuery q, HttpServletRequest req) {
        Page<InstanceRow> page = history.instances(actor, q == null ? new InstanceQuery(null, null, null, null, null, null, null, null, null, null) : q);
        People people = new People(directory, actor.tenant(), page.list().stream().map(InstanceRow::createdBy).toList());
        String locale = Present.locale(req);
        return page.map(r -> row(r, people, locale));
    }

    /** 对账：该业务对象的全部实例、审批与流转（超过上限时 truncated=true，只含最新的）。 */
    @GetMapping("/subjects/{type}/{ref}/history")
    public SubjectDto subject(Actor actor, @PathVariable("type") String type, @PathVariable("ref") String ref, HttpServletRequest req) {
        SubjectHistory s = history.subject(actor, type, ref);
        List<String> principals = new ArrayList<>();
        s.instances().forEach(i -> {
            principals.add(i.instance().createdBy());
            i.approvals().forEach(a -> principals.add(a.requestedBy()));
            i.history().forEach(h -> principals.add(h.actor()));
        });
        People people = new People(directory, actor.tenant(), principals);
        String locale = Present.locale(req);
        return new SubjectDto(s.type(), s.ref(), s.total(), s.truncated(), s.instances().stream().map(i -> new SubjectItem(row(i.instance(), people, locale),
                i.approvals().stream().map((ApprovalRow a) -> new ApprovalBrief(a.approvalNo(), a.status(), a.round(), people.of(a.requestedBy()),
                        a.requestedAtMs(), a.finishedAtMs())).toList(),
                i.history().stream().map(h -> entry(h, people, locale)).toList())).toList());
    }

    /** 流程统计（需 report:view）。 */
    @PostMapping("/process-stats")
    public List<StatsDto> stats(Actor actor, @RequestBody(required = false) StatsQuery q, HttpServletRequest req) {
        StatsQuery query = q == null ? new StatsQuery(null, null, null) : q;
        String locale = Present.locale(req);
        return history.stats(actor, query.processCode(), query.fromMs(), query.toMs()).stream().map((ProcessStats s) -> new StatsDto(
                Present.named(s.processCode(), s.processName(), locale), s.total(), s.open(), s.byCategory(), s.avgCycleMs(), s.approvals())).toList();
    }

    private static Row row(InstanceRow r, People people, String locale) {
        return new Row(r.instanceNo(), Present.named(r.processCode(), r.processName(), locale), r.processVersion(), Present.subject(r.subject()),
                Present.named(r.status(), r.statusName(), locale), r.statusCategory(), r.open(), people.of(r.createdBy()), r.createdAtMs(), r.updatedAtMs(),
                r.closedAtMs());
    }

    private static Entry entry(HistoryEntry h, People people, String locale) {
        boolean member = h.actor() != null && h.actor().startsWith("member:");
        return new Entry(h.entityKind(), h.entityNo(), h.action(), Present.text(h.actionName(), locale), h.from(), Present.text(h.fromName(), locale),
                h.to(), Present.text(h.toName(), locale), h.seq(), member ? people.of(h.actor()) : null, member ? null : h.actor(), h.reason(),
                h.payload() == null || h.payload().isEmpty() ? null : h.payload(), h.atMs());
    }
}
