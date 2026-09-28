package ai.neargo.opskit.svc.api;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 实例历史 · 实例查询 · 对账（按业务对象）· 流程统计（21/06 §四 · 21/07 O2-10）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class InstanceController {

    private final ProcessHistoryService history;
    private final DirectoryAppService directory;

    public InstanceController(ProcessHistoryService history, DirectoryAppService directory) {
        this.history = history;
        this.directory = directory;
    }

    public record Row(String instanceNo, Named process, String processVersion, Subject subject, Named status, String statusCategory, boolean open,
            Person createdBy, long createdAtMs, long updatedAtMs, Long closedAtMs) {
    }

    /**
     * @param actor     成员给 {ref, displayName}；应用 / 系统操作时为空，看 {@code actorText}（{@code app:x} / {@code system}）
     * @param entityKind INSTANCE / TASK / APPROVAL / SLA / GATE
     */
    public record Entry(String entityKind, String entityNo, String action, String from, String to, Integer seq, Person actor, String actorText,
            String reason, Map<String, Object> payload, long atMs) {
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

    @GetMapping("/instances/{no}/history")
    public List<Entry> history(Actor actor, @PathVariable("no") String no) {
        List<HistoryEntry> hs = history.history(actor, no);
        People people = new People(directory, actor.tenant(), hs.stream().map(HistoryEntry::actor).toList());
        return hs.stream().map(h -> entry(h, people)).toList();
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
                i.history().stream().map(h -> entry(h, people)).toList())).toList());
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

    private static Entry entry(HistoryEntry h, People people) {
        boolean member = h.actor() != null && h.actor().startsWith("member:");
        return new Entry(h.entityKind(), h.entityNo(), h.action(), h.from(), h.to(), h.seq(), member ? people.of(h.actor()) : null,
                member ? null : h.actor(), h.reason(), h.payload() == null || h.payload().isEmpty() ? null : h.payload(), h.atMs());
    }
}
