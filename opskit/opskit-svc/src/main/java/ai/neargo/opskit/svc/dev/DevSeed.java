package ai.neargo.opskit.svc.dev;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupUpsert;
import ai.neargo.opskit.platform.api.directory.MemberAttrDef;
import ai.neargo.opskit.platform.api.directory.MemberUpsert;
import ai.neargo.opskit.platform.api.directory.RoleBinding;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.api.directory.SyncBatch;
import ai.neargo.opskit.platform.api.federation.ClaimMapping;
import ai.neargo.opskit.platform.api.federation.IdpConfig;
import ai.neargo.opskit.platform.api.federation.IdpKind;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.QueueTask;
import ai.neargo.opskit.process.api.model.query.TaskQuery;
import ai.neargo.opskit.process.core.app.InstanceAppService;
import ai.neargo.opskit.process.core.app.ApprovalTemplateAppService;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import ai.neargo.opskit.process.core.app.ProcessQueryService;
import ai.neargo.opskit.process.core.app.TaskAppService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地联调种子（21/07 O4-0 D；场景见 22/08）。local 配置档用内存库，每次启动重建，所以单号每次不同 ——
 * 以 {@code GET /dev/seed} 返回的清单为准（opskit/scripts/dev-token.sh fixtures 可直接生成前端夹具）。
 *
 * <ul>
 *   <li>S2-1：bob 提交的两步审批单，第二步是条件会签且条件成立；alice 是第一步候选、不是提交人</li>
 *   <li>S2-2：alice 自己提交的待审单（自审负对照）</li>
 *   <li>S1 负对照：打了 site:B 标签的待审单 —— alice 是候选，但令牌只带 site:A，不进她的待办</li>
 *   <li>S3：队列组 review-queue 3 条任务，frank 领着 1 条（领取超时 120 秒）；这 3 张单挂了 SLA 策略 review_sla（4 小时，80% 预警）</li>
 *   <li>工单：报修类型 repair（外勤队列 field-ops，alice / frank 为组员）；ticketAssigned 派给 alice，ticketQueued 在队列，ticketUrgent 为 P1</li>
 *   <li>S5：viewer 令牌只带 flow:todo:read · flow:approval:read · flow:ticket:read；目录里是 auditor（能看全部审批），写接口一律 403</li>
 * </ul>
 */
public class DevSeed {

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);
    public static final String TENANT = "T001";

    public record Persona(String externalRef, String memberId, String displayName, List<String> tokenPerms, List<String> tokenScopes, String note) {
    }

    public record Manifest(String accountId, String workspaceId, String tenant, String workspacePath, Map<String, Persona> personas,
            Map<String, Object> fixtures) {
    }

    private final TenancyAppService tenancy;
    private final FederationAppService federation;
    private final DirectoryAppService directory;
    private final MemberAttrAppService attrs;
    private final ApprovalTemplateAppService templates;
    private final SlaPolicyAppService slaPolicies;
    private final ai.neargo.opskit.process.core.app.ProcessDefinitionAppService processDefs;
    private final ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService ticketDefs;
    private final ai.neargo.opskit.ticket.core.app.TicketAppService tickets;
    private final InstanceAppService instances;
    private final TaskAppService tasks;
    private final ProcessQueryService queries;
    private final String jwksUrl;
    private volatile Manifest manifest;

    public DevSeed(TenancyAppService tenancy, FederationAppService federation, DirectoryAppService directory, MemberAttrAppService attrs,
            ApprovalTemplateAppService templates, SlaPolicyAppService slaPolicies,
            ai.neargo.opskit.process.core.app.ProcessDefinitionAppService processDefs, ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService ticketDefs,
            ai.neargo.opskit.ticket.core.app.TicketAppService tickets, InstanceAppService instances, TaskAppService tasks, ProcessQueryService queries, String jwksUrl) {
        this.tenancy = tenancy;
        this.federation = federation;
        this.directory = directory;
        this.attrs = attrs;
        this.templates = templates;
        this.slaPolicies = slaPolicies;
        this.processDefs = processDefs;
        this.ticketDefs = ticketDefs;
        this.tickets = tickets;
        this.instances = instances;
        this.tasks = tasks;
        this.queries = queries;
        this.jwksUrl = jwksUrl;
    }

    public Manifest manifest() {
        if (manifest == null) {
            throw new IllegalStateException("dev seed not run yet");
        }
        return manifest;
    }

    public synchronized Manifest run() {
        if (manifest != null) {
            return manifest;
        }
        AccountId account = new AccountId(tenancy.createAccount(new CreateAccountRequest("本地联调运营商", AccountKind.DIRECT)).accountId());
        WorkspaceView wsView = tenancy.createWorkspace(account, new CreateWorkspaceRequest("本地联调空间", TENANT, "Asia/Shanghai", "zh"));
        WorkspaceId ws = new WorkspaceId(wsView.workspaceId());
        TenantContext t = new TenantContext(account, ws);
        Actor app = new Actor(t, Optional.empty(), Optional.of("dev-seed"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        federation.upsert(account, new IdpConfig(DevIdentityProvider.ISSUER, IdpKind.JWT, jwksUrl, null, null, null, null, null,
                new ClaimMapping("sub", "name", "tenant", null, null, "perms", "scopes"), true));

        directory.upsertRole(app, ws, new RoleUpsert("approver", Map.of("zh", "审批人", "en", "Approver"), Set.of("approval:decide")));
        directory.upsertRole(app, ws, new RoleUpsert("auditor", Map.of("zh", "审计", "en", "Auditor"), Set.of("approval:view:all", "ticket:view:all")));
        directory.upsertRole(app, ws, new RoleUpsert("ticket-agent", Map.of("zh", "工单处理", "en", "Ticket agent"), Set.of("ticket:create", "ticket:view:all",
                "ticket:work", "ticket:assign", "ticket:escalate", "ticket:merge", "ticket:comment:internal")));
        directory.upsertRole(app, ws, new RoleUpsert("reporter", Map.of("zh", "报单人", "en", "Reporter"), Set.of("ticket:create", "ticket:view:own")));
        directory.upsertRole(app, ws, new RoleUpsert("flow-admin", Map.of("zh", "流程管理员", "en", "Flow admin"),
                Set.of("process:manage", "sla:manage", "report:view", "approval:view:all", "directory:manage", "ticket-type:manage",
                "ticket:view:all")));
        attrs.upsert(app, ws, new MemberAttrDef("partyType", Map.of("zh", "身份", "en", "Party", "ar", "الجهة"),
                Map.of("STAFF", Map.of("zh", "员工", "en", "Staff", "ar", "موظف"), "AGENT", Map.of("zh", "代理", "en", "Agent", "ar", "وكيل"))));
        directory.sync(app, new SyncBatch(SyncBatch.Mode.MERGE,
                List.of(member("u_alice", "Alice 审批人", "STAFF"), member("u_bob", "Bob 业务员", "AGENT"), member("u_dave", "Dave 财务", "STAFF"),
                        member("u_erin", "Erin 财务", "STAFF"), member("u_frank", "Frank 审核员", "STAFF"), member("u_viewer", "Vera 只读", "STAFF"),
                        member("u_auditor", "Axel 审计", "STAFF"), member("u_admin", "Ada 流程管理员", "STAFF")),
                List.of(new GroupUpsert("finance", Map.of("zh", "财务组", "en", "Finance"), GroupKind.TEAM, null, null),
                        new GroupUpsert("review-queue", Map.of("zh", "证据审核队列", "en", "Evidence review"), GroupKind.QUEUE, 120, "CREATED"),
                        new GroupUpsert("field-ops", Map.of("zh", "外勤维修", "en", "Field ops"), GroupKind.QUEUE, 1800, "PRIORITY")),
                Map.of("finance", List.of("u_dave", "u_erin"), "review-queue", List.of("u_alice", "u_frank"), "field-ops", List.of("u_alice", "u_frank")),
                List.of(new RoleBinding("approver", "member:@u_alice"), new RoleBinding("auditor", "member:@u_auditor"), new RoleBinding("auditor", "member:@u_viewer"), new RoleBinding("flow-admin", "member:@u_admin"), new RoleBinding("ticket-agent", "member:@u_alice"),
                new RoleBinding("reporter", "member:@u_bob"))));

        templates.publish(app, ws, contractTemplate(), "local seed", "API");
        templates.publish(app, ws, evidenceTemplate(), "local seed", "API");
        slaPolicies.publish(app, ws, new SlaPolicyDef("review_sla", t("审核时限", "Review SLA", "مهلة المراجعة"),
                List.of(new SlaPolicyDef.Metric("RESOLUTION", t("审核完成", "Reviewed", "تمت المراجعة"), null, null, null)),
                List.of(new SlaPolicyDef.Target(null, Map.of("RESOLUTION", java.time.Duration.ofHours(4)), null)), 80), "local seed", "API");

        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("alice", persona(t, "u_alice", List.of(), List.of("site:A"), "第一步候选（角色 approver）· review-queue 组员 · 令牌带 site:A"));
        personas.put("bob", persona(t, "u_bob", List.of(), List.of(), "提交人"));
        personas.put("dave", persona(t, "u_dave", List.of(), List.of(), "finance 组员（会签）"));
        personas.put("erin", persona(t, "u_erin", List.of(), List.of(), "finance 组员（会签）"));
        personas.put("frank", persona(t, "u_frank", List.of(), List.of(), "review-queue 组员，领着一条任务"));
        personas.put("viewer", persona(t, "u_viewer", List.of("flow:todo:read", "flow:approval:read", "flow:ticket:read"), List.of(), "只读（S5）：能看全部审批（角色 auditor），不能审批、不能领取"));
        personas.put("auditor", persona(t, "u_auditor", List.of(), List.of(), "approval:view:all"));
        personas.put("admin", persona(t, "u_admin", List.of(), List.of(), "process:manage · sla:manage · report:view：发布模板 / SLA 策略、看流程统计"));

        Actor bob = actor(t, personas.get("bob"), Set.of());
        Actor alice = actor(t, personas.get("alice"), Set.of("review-queue"));
        Map<String, Object> fx = new LinkedHashMap<>();
        fx.put("approvalTwoStep", submit(app, bob, "contract_approval", "C-1001", "合同 C-1001 · 华东区代理", Map.of("needsCosign", true), Set.of()));
        fx.put("approvalMine", submit(app, alice, "contract_approval", "C-1002", "合同 C-1002 · 自己提交", Map.of("needsCosign", false), Set.of()));
        fx.put("approvalHidden", submit(app, bob, "contract_approval", "C-1003", "合同 C-1003 · site:B", Map.of("needsCosign", false), Set.of("site:B")));
        for (int i = 1; i <= 3; i++) {
            submit(app, bob, "evidence_review", "E-10" + i, "证据包 E-10" + i, Map.of(), Set.of(), List.of("review_sla"));
        }
        List<QueueTask> queue = queries.tasks(app, new TaskQuery("review-queue", List.of("OPEN"), null, null, 1, 50)).list();
        Actor frank = actor(t, personas.get("frank"), Set.of("review-queue"));
        tasks.claim(frank, queue.get(0).taskNo());
        fx.put("queueGroup", "review-queue");
        fx.put("queueTasks", queue.stream().map(QueueTask::taskNo).toList());
        fx.put("claimedByFrank", queue.get(0).taskNo());
        fx.put("claimTimeoutSec", 120);

        // 工单（O3 联调）：报修流程 + SLA + 类型；三张单：派给 alice 的 / 在外勤队列里的 / 紧急的
        processDefs.publish(app, ws, repairFlow(), "local seed", "API");
        slaPolicies.publish(app, ws, repairSla(), "local seed", "API");
        ticketDefs.publishType(app, ws, repairType(), "local seed", "API");
        String assigned = ticket(app, bob, "屏幕无响应", "S1", "CAB-001", "APP", "P2");
        tickets.assign(app, assigned, personas.get("alice").memberId(), null);
        fx.put("ticketAssigned", assigned);
        fx.put("ticketQueued", ticket(app, bob, "归还后未结束订单", "S2", "CAB-002", "CS", "P3"));
        fx.put("ticketUrgent", ticket(app, bob, "整柜离线", "S1", "CAB-003", "OPS", "P1"));
        fx.put("ticketQueue", "field-ops");

        manifest = new Manifest(account.value(), ws.value(), TENANT, "ext:" + TENANT, personas, fx);
        log.info("本地联调种子已写入 account={} workspace={} tenant={}", account.value(), ws.value(), TENANT);
        return manifest;
    }

    private String ticket(Actor app, Actor requester, String title, String site, String cabinet, String source, String priority) {
        return tickets.create(app, new ai.neargo.opskit.ticket.api.model.CreateTicketRequest("repair", title, "本地联调种子", Map.of("site", site,
                "source", source, "sourceRef", "SR-" + cabinet), priority, requester.member().orElseThrow().key(), Set.of(), null,
                new SubjectRef("cabinet", cabinet, Map.of("title", "柜机 " + cabinet)))).ticket().ticketNo();
    }

    /** 报修：新建 →受理→ 处理中 ⇄（追问 / 回复）待回复；处理中 →解决→ 已解决；任意 →作废→ 已作废。 */
    static ai.neargo.opskit.process.api.definition.ProcessDef repairFlow() {
        var any = new ai.neargo.opskit.process.api.definition.Who(Set.of("REQUESTER", "ASSIGNEE"), Set.of(), Set.of(), Set.of());
        var assignee = ai.neargo.opskit.process.api.definition.Who.ASSIGNEE;
        return new ai.neargo.opskit.process.api.definition.ProcessDef("repair_flow", t("报修流程", "Repair flow", "سير الإصلاح"), "new", List.of(
                new ai.neargo.opskit.process.api.definition.StatusDef("new", t("新建", "New", "جديد"), ai.neargo.opskit.process.api.definition.StatusCategory.NEW, null),
                new ai.neargo.opskit.process.api.definition.StatusDef("doing", t("处理中", "In progress", "قيد المعالجة"),
                        ai.neargo.opskit.process.api.definition.StatusCategory.IN_PROGRESS, null),
                new ai.neargo.opskit.process.api.definition.StatusDef("waiting", t("待回复", "Waiting", "بانتظار الرد"),
                        ai.neargo.opskit.process.api.definition.StatusCategory.WAITING, null),
                new ai.neargo.opskit.process.api.definition.StatusDef("done", t("已解决", "Resolved", "تم الحل"), ai.neargo.opskit.process.api.definition.StatusCategory.DONE, null),
                new ai.neargo.opskit.process.api.definition.StatusDef("cancelled", t("已作废", "Cancelled", "ملغى"),
                        ai.neargo.opskit.process.api.definition.StatusCategory.CANCELLED, null)),
                List.of(new ai.neargo.opskit.process.api.definition.TransitionDef("accept", t("受理", "Accept", "قبول"), Set.of("new"), "doing", assignee, null, null, null, false),
                        new ai.neargo.opskit.process.api.definition.TransitionDef("ask", t("追问", "Ask", "استفسار"), Set.of("doing"), "waiting", assignee, null, null, null, false),
                        new ai.neargo.opskit.process.api.definition.TransitionDef("reply", t("回复", "Reply", "رد"), Set.of("waiting"), "doing", any, null, null, null, false),
                        new ai.neargo.opskit.process.api.definition.TransitionDef("resolve", t("解决", "Resolve", "حل"), Set.of("doing"), "done", assignee, null,
                                new ai.neargo.opskit.process.api.definition.Requires(Set.of(), true), null, false),
                        new ai.neargo.opskit.process.api.definition.TransitionDef("cancel", t("作废", "Cancel", "إلغاء"), Set.of("new", "doing", "waiting"), "cancelled",
                                any, null, new ai.neargo.opskit.process.api.definition.Requires(Set.of(), true), null, false)),
                Set.of());
    }

    /** 首次响应（受理即停）与解决；P1 1 小时 / 4 小时，其余 4 小时 / 24 小时，80% 预警。 */
    static SlaPolicyDef repairSla() {
        return new SlaPolicyDef("repair_sla", t("报修时限", "Repair SLA", "مهلة الإصلاح"), List.of(
                new SlaPolicyDef.Metric("FIRST_RESPONSE", t("首次响应", "First response", "الاستجابة الأولى"), null, null,
                        new SlaPolicyDef.Trigger(false, Set.of(), Set.of("accept"))),
                new SlaPolicyDef.Metric("RESOLUTION", t("解决", "Resolution", "الحل"), null, null, null)),
                List.of(new SlaPolicyDef.Target(new ai.neargo.opskit.commons.condition.Condition.Compare("priority", ai.neargo.opskit.commons.condition.Op.EQ, "P1"),
                                Map.of("FIRST_RESPONSE", java.time.Duration.ofHours(1), "RESOLUTION", java.time.Duration.ofHours(4)), null),
                        new SlaPolicyDef.Target(null, Map.of("FIRST_RESPONSE", java.time.Duration.ofHours(4), "RESOLUTION", java.time.Duration.ofHours(24)), null)),
                80);
    }

    static ai.neargo.opskit.ticket.api.definition.TicketTypeDef repairType() {
        var opt = (java.util.function.BiFunction<String, LocalizedText, ai.neargo.opskit.ticket.api.definition.OptionDef>)
                (c, n) -> new ai.neargo.opskit.ticket.api.definition.OptionDef(c, n, true);
        return new ai.neargo.opskit.ticket.api.definition.TicketTypeDef("repair", t("设备报修", "Device repair", "إصلاح جهاز"), "repair_flow", List.of(
                new ai.neargo.opskit.ticket.api.definition.FieldDef("site", t("站点", "Site", "الموقع"), ai.neargo.opskit.ticket.api.definition.FieldType.SELECT,
                        true, true, true, ai.neargo.opskit.ticket.api.definition.Options.of(List.of(opt.apply("S1", t("一号店", "Store 1", "المتجر 1")),
                                opt.apply("S2", t("二号店", "Store 2", "المتجر 2")))), false, null),
                new ai.neargo.opskit.ticket.api.definition.FieldDef("source", t("来源", "Source", "المصدر"), ai.neargo.opskit.ticket.api.definition.FieldType.SELECT,
                        true, true, true, ai.neargo.opskit.ticket.api.definition.Options.of(List.of(opt.apply("APP", t("用户报障", "User report", "بلاغ مستخدم")),
                                opt.apply("OPS", t("巡检", "Inspection", "تفتيش")), opt.apply("CS", t("客服", "Customer service", "خدمة العملاء")))), false, null),
                new ai.neargo.opskit.ticket.api.definition.FieldDef("sourceRef", t("来源单号", "Source ref", "مرجع المصدر"),
                        ai.neargo.opskit.ticket.api.definition.FieldType.TEXT, false, true, true, null, false, null)),
                ai.neargo.opskit.ticket.api.definition.Options.of(List.of(opt.apply("P1", t("紧急", "Urgent", "عاجل")), opt.apply("P2", t("高", "High", "مرتفع")),
                        opt.apply("P3", t("普通", "Normal", "عادي")))),
                ai.neargo.opskit.ticket.api.definition.Options.of(List.of(opt.apply("FIXED", t("已修复", "Fixed", "تم الإصلاح")),
                        opt.apply("NO_FAULT", t("无故障", "No fault", "لا عطل")))), "RP",
                new ai.neargo.opskit.ticket.api.definition.AssignmentDef(ai.neargo.opskit.ticket.api.definition.AssignmentDef.Strategy.QUEUE, "field-ops", null, null, null),
                List.of("repair_sla"), List.of(new ai.neargo.opskit.ticket.api.definition.TicketTypeDef.ScopeFrom("site", "site")), null, "cancel");
    }

    /** 提交一张单并返回 {instanceNo, approvalNo}。 */
    private Map<String, String> submit(Actor app, Actor requester, String process, String ref, String title, Map<String, Object> fields,
            Set<String> scopes) {
        return submit(app, requester, process, ref, title, fields, scopes, List.of());
    }

    private Map<String, String> submit(Actor app, Actor requester, String process, String ref, String title, Map<String, Object> fields,
            Set<String> scopes, List<String> slaPolicies) {
        String no = instances.start(requester, new StartRequest(process, new SubjectRef(process.startsWith("contract") ? "contract" : "evidence", ref,
                Map.of("title", title)), fields, Map.of(), scopes, slaPolicies)).instanceNo();
        instances.transition(requester, no, "submit", TransitionRequest.empty());
        String approvalNo = queries.approvals(app, new ai.neargo.opskit.process.api.model.query.ApprovalQuery(null, null, null, null, no, 1, 1))
                .list().get(0).approvalNo();
        return Map.of("instanceNo", no, "approvalNo", approvalNo, "subjectRef", ref);
    }

    private Persona persona(TenantContext t, String ref, List<String> perms, List<String> scopes, String note) {
        var m = directory.findMemberByExternalRef(t, ref).orElseThrow();
        return new Persona(ref, m.memberId(), m.displayName(), perms, scopes, note);
    }

    private Actor actor(TenantContext t, Persona p, Set<String> groups) {
        Set<String> roles = directory.effective(t, p.memberId(), Set.of(), Set.of()).roles();
        return new Actor(t, Optional.of(PrincipalRef.member(p.memberId())), Optional.empty(), groups, roles, Set.copyOf(p.tokenPerms()), Set.of(),
                Set.copyOf(p.tokenScopes()));
    }

    private static MemberUpsert member(String ref, String name, String party) {
        return new MemberUpsert(ref, name, Map.of("partyType", party), Set.of());
    }

    private static LocalizedText t(String zh, String en, String ar) {
        return new LocalizedText(Map.of("zh", zh, "en", en, "ar", ar));
    }

    /** 两步：运营审批（角色 approver，或签）→ 财务会签（组 finance，仅当 needsCosign 为真）。 */
    static ApprovalTemplate contractTemplate() {
        return new ApprovalTemplate("contract_approval", t("合同审批", "Contract approval", "اعتماد العقد"), List.of(
                new ApprovalTemplate.Step("ops", t("运营审批", "Ops review", "مراجعة العمليات"),
                        new ApprovalTemplate.Approver(ApproverKind.ROLE, List.of("approver")), "ANY", null, 24),
                new ApprovalTemplate.Step("finance", t("财务会签", "Finance countersign", "توقيع المالية"),
                        new ApprovalTemplate.Approver(ApproverKind.GROUP, List.of("finance")), "ALL", new ApprovalTemplate.When("needsCosign", "TRUE", null), 48)),
                ApprovalTemplate.Rules.DEFAULT);
    }

    /** 队列审核：一步，审批人是队列组 review-queue（任务进队列，领取后办理）。 */
    static ApprovalTemplate evidenceTemplate() {
        return new ApprovalTemplate("evidence_review", t("证据审核", "Evidence review", "مراجعة الأدلة"), List.of(
                new ApprovalTemplate.Step("review", t("证据审核", "Evidence review", "مراجعة الأدلة"),
                        new ApprovalTemplate.Approver(ApproverKind.GROUP, List.of("review-queue")), "ANY", null, 4)),
                ApprovalTemplate.Rules.DEFAULT);
    }
}
