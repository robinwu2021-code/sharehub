package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.model.ApprovalView;
import ai.neargo.opskit.process.api.model.query.ApprovalDetail;
import ai.neargo.opskit.process.api.model.query.ApprovalQuery;
import ai.neargo.opskit.process.api.model.query.ApprovalRow;
import ai.neargo.opskit.process.core.app.ApprovalAppService;
import ai.neargo.opskit.process.core.app.ProcessQueryService;
import ai.neargo.opskit.svc.api.Present.Named;
import ai.neargo.opskit.svc.api.Present.People;
import ai.neargo.opskit.svc.api.Present.Person;
import ai.neargo.opskit.svc.api.Present.Subject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 审批（21/06 §四 · §七 ApprovalRow / ApprovalView）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class ApprovalController {

    private final ProcessQueryService queries;
    private final ApprovalAppService approvals;
    private final DirectoryAppService directory;

    public ApprovalController(ProcessQueryService queries, ApprovalAppService approvals, DirectoryAppService directory) {
        this.queries = queries;
        this.approvals = approvals;
        this.directory = directory;
    }

    public record Row(String approvalNo, String instanceNo, Named process, Subject subject, String status, Named currentStep, int round,
            Person requestedBy, long requestedAtMs, Long finishedAtMs, Long stepStartedAtMs, Long stepDueAtMs) {
    }

    public record Step(String code, String name, String approverText, String conditionText, String state, String decidedBy, Long decidedAtMs) {
    }

    public record Round(int round, String status, long atMs, String by, String comment) {
    }

    public record Mine(String taskNo, boolean canDecide, String whyNot, boolean canWithdraw) {
    }

    public record Rules(boolean crossStepExclusive) {
    }

    /** ApprovalRow 全部 + 详情字段（21/06 §七）。facts / evidence 由业务提交时给出，本期流程层还没有这两项，恒为空。 */
    public record View(String approvalNo, String instanceNo, Named process, Subject subject, String status, Named currentStep, int round,
            Person requestedBy, long requestedAtMs, Long finishedAtMs, Long stepStartedAtMs, Long stepDueAtMs, List<Map<String, String>> facts,
            List<Step> steps, List<Round> rounds, List<Map<String, Object>> evidence, Mine mine, Rules rules) {
    }

    public record DecideBody(String taskNo, String decision, String reason) {
    }

    @PostMapping("/approvals/query")
    public Page<Row> query(Actor actor, @RequestBody(required = false) ApprovalQuery q, HttpServletRequest req) {
        Page<ApprovalRow> page = queries.approvals(actor, orEmpty(q));
        People people = new People(directory, actor.tenant(), page.list().stream().map(ApprovalRow::requestedBy).toList());
        String locale = Present.locale(req);
        return page.map(r -> row(r, people, locale));
    }

    @PostMapping("/approvals/count")
    public Map<String, Long> count(Actor actor, @RequestBody(required = false) ApprovalQuery q) {
        return queries.approvalCount(actor, orEmpty(q));
    }

    @GetMapping("/approvals/{no}")
    public View get(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        return view(actor, no, approvals.get(actor, no), Present.locale(req));
    }

    /** 校验顺序由用例保证：409（任务已处理）→ 403（规则）→ 422（驳回缺原因），21/02 §8.3。 */
    @PostMapping("/approvals/{no}/decide")
    public View decide(Actor actor, @PathVariable("no") String no, @RequestBody DecideBody body, HttpServletRequest req) {
        return view(actor, no, approvals.decide(actor, no, body.taskNo(), approve(body.decision()), body.reason()), Present.locale(req));
    }

    @PostMapping("/approvals/{no}/withdraw")
    public View withdraw(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        return view(actor, no, approvals.withdraw(actor, no), Present.locale(req));
    }

    static boolean approve(String decision) {
        if ("APPROVE".equals(decision)) {
            return true;
        }
        if ("REJECT".equals(decision)) {
            return false;
        }
        throw TodoTaskController.invalid("decision", "opskit.validation.one_of");
    }

    private static ApprovalQuery orEmpty(ApprovalQuery q) {
        return q == null ? new ApprovalQuery(null, null, null, null, null, null, null) : q;
    }

    private static Row row(ApprovalRow r, People people, String locale) {
        return new Row(r.approvalNo(), r.instanceNo(), Present.named(r.processCode(), r.processName(), locale), Present.subject(r.subject()),
                r.status(), r.currentStep() == null ? null : Present.named(r.currentStep(), r.currentStepName(), locale), r.round(),
                people.of(r.requestedBy()), r.requestedAtMs(), r.finishedAtMs(), r.stepStartedAtMs(), r.stepDueAtMs());
    }

    private View view(Actor actor, String no, ApprovalView v, String locale) {
        ApprovalDetail d = queries.approvalDetail(actor, no).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "approval not found"));
        ApprovalView.RoundView current = v.rounds().stream().filter(r -> r.round() == v.currentRound()).findFirst().orElse(null);
        List<String> principals = new ArrayList<>();
        principals.add(v.requestedBy());
        v.rounds().forEach(r -> {
            principals.add(r.submittedBy());
            r.steps().forEach(s -> s.decisions().forEach(x -> principals.add(x.decidedBy())));
        });
        d.steps().forEach(s -> {
            if (s.approvers().kind() == ai.neargo.opskit.process.api.definition.ApproverKind.MEMBER) {
                s.approvers().values().forEach(m -> principals.add("member:" + m));
            }
        });
        People people = new People(directory, actor.tenant(), principals);
        Row r = row(d.row(), people, locale);

        List<Step> steps = new ArrayList<>();
        for (ApprovalStepDef def : d.steps()) {
            ApprovalView.StepView sv = current == null ? null
                    : current.steps().stream().filter(s -> s.code().equals(def.code())).findFirst().orElse(null);
            ApprovalView.Decision decided = sv == null ? null
                    : sv.decisions().stream().filter(x -> x.decision() != null).reduce((a, b) -> b).orElse(null);
            Person by = decided == null ? null : people.of(decided.decidedBy());
            steps.add(new Step(def.code(), Present.text(def.name(), locale), approverText(actor, def.approvers(), people, locale),
                    def.when() == null ? null : conditionText(locale), sv == null ? "TODO" : sv.state(),
                    by == null ? null : Objects.requireNonNullElse(by.displayName(), by.ref()), decided == null ? null : decided.atMs()));
        }
        List<Round> rounds = v.rounds().stream().map(x -> {
            Person by = people.of(x.submittedBy());
            String comment = x.steps().stream().flatMap(s -> s.decisions().stream()).filter(dd -> "REJECT".equals(dd.decision()))
                    .map(ApprovalView.Decision::reason).filter(Objects::nonNull).findFirst().orElse(null);
            return new Round(x.round(), x.result() == null ? "IN_PROGRESS" : x.result(), x.finishedAtMs() != null ? x.finishedAtMs() : x.submittedAtMs(),
                    by == null ? null : Objects.requireNonNullElse(by.displayName(), by.ref()), comment);
        }).toList();
        ApprovalView.Mine m = v.mine();
        return new View(r.approvalNo(), r.instanceNo(), r.process(), r.subject(), r.status(), r.currentStep(), r.round(), r.requestedBy(),
                r.requestedAtMs(), r.finishedAtMs(), r.stepStartedAtMs(), r.stepDueAtMs(), List.of(), steps, rounds, List.of(),
                new Mine(m.taskNo(), m.canDecide(), m.whyNot(), m.canWithdraw()), new Rules(d.rules().crossStepExclusive()));
    }

    /** 审批人说明（服务按语言给好）。组给组名，成员给显示名；角色 / 权限 / 字段给代号。 */
    private String approverText(Actor actor, ApproverRef ref, People people, String locale) {
        List<String> parts = switch (ref.kind()) {
            case MEMBER -> ref.values().stream().map(v -> {
                Person p = people.of("member:" + v);
                return Objects.requireNonNullElse(p.displayName(), v);
            }).toList();
            case GROUP -> ref.values().stream().map(g -> directory.findGroup(actor.tenant(), g).filter(x -> !x.name().isEmpty())
                    .map(x -> new ai.neargo.opskit.commons.i18n.LocalizedText(x.name()).resolve(locale, "en")).orElse(g)).toList();
            default -> ref.values();
        };
        String label = LABELS.getOrDefault(locale, LABELS.get("en")).get(ref.kind().name());
        return label + " " + String.join(locale.equals("zh") ? "、" : ", ", parts);
    }

    private static String conditionText(String locale) {
        return LABELS.getOrDefault(locale, LABELS.get("en")).get("CONDITIONAL");
    }

    private static final Map<String, Map<String, String>> LABELS = Map.of(
            "zh", Map.of("MEMBER", "成员", "GROUP", "组", "ROLE", "角色", "PERM", "持权限", "FIELD", "字段指定", "PARTICIPANT", "参与者",
                    "CONDITIONAL", "满足条件时进入此步"),
            "en", Map.of("MEMBER", "Member", "GROUP", "Group", "ROLE", "Role", "PERM", "Holder of", "FIELD", "Field", "PARTICIPANT", "Participant",
                    "CONDITIONAL", "Entered only when its condition holds"),
            "ar", Map.of("MEMBER", "عضو", "GROUP", "مجموعة", "ROLE", "دور", "PERM", "صاحب صلاحية", "FIELD", "حقل", "PARTICIPANT", "مشارك",
                    "CONDITIONAL", "تُدخل هذه الخطوة عند تحقق شرطها"));
}
