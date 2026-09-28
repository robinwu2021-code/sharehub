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
 *   <li>S3：队列组 review-queue 3 条任务，frank 领着 1 条（领取超时 120 秒）</li>
 *   <li>S5：viewer 令牌只带 flow:todo:read · flow:approval:read · flow:ticket:read</li>
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
    private final InstanceAppService instances;
    private final TaskAppService tasks;
    private final ProcessQueryService queries;
    private final String jwksUrl;
    private volatile Manifest manifest;

    public DevSeed(TenancyAppService tenancy, FederationAppService federation, DirectoryAppService directory, MemberAttrAppService attrs,
            ApprovalTemplateAppService templates, InstanceAppService instances, TaskAppService tasks, ProcessQueryService queries, String jwksUrl) {
        this.tenancy = tenancy;
        this.federation = federation;
        this.directory = directory;
        this.attrs = attrs;
        this.templates = templates;
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
        directory.upsertRole(app, ws, new RoleUpsert("auditor", Map.of("zh", "审计", "en", "Auditor"), Set.of("approval:view:all")));
        directory.upsertRole(app, ws, new RoleUpsert("flow-admin", Map.of("zh", "流程管理员", "en", "Flow admin"),
                Set.of("process:manage", "approval:view:all", "directory:manage")));
        attrs.upsert(app, ws, new MemberAttrDef("partyType", Map.of("zh", "身份", "en", "Party", "ar", "الجهة"),
                Map.of("STAFF", Map.of("zh", "员工", "en", "Staff", "ar", "موظف"), "AGENT", Map.of("zh", "代理", "en", "Agent", "ar", "وكيل"))));
        directory.sync(app, new SyncBatch(SyncBatch.Mode.MERGE,
                List.of(member("u_alice", "Alice 审批人", "STAFF"), member("u_bob", "Bob 业务员", "AGENT"), member("u_dave", "Dave 财务", "STAFF"),
                        member("u_erin", "Erin 财务", "STAFF"), member("u_frank", "Frank 审核员", "STAFF"), member("u_viewer", "Vera 只读", "STAFF"),
                        member("u_auditor", "Axel 审计", "STAFF"), member("u_admin", "Ada 流程管理员", "STAFF")),
                List.of(new GroupUpsert("finance", Map.of("zh", "财务组", "en", "Finance"), GroupKind.TEAM, null, null),
                        new GroupUpsert("review-queue", Map.of("zh", "证据审核队列", "en", "Evidence review"), GroupKind.QUEUE, 120, "CREATED")),
                Map.of("finance", List.of("u_dave", "u_erin"), "review-queue", List.of("u_alice", "u_frank")),
                List.of(new RoleBinding("approver", "member:@u_alice"), new RoleBinding("auditor", "member:@u_auditor"), new RoleBinding("flow-admin", "member:@u_admin"))));

        templates.publish(app, ws, contractTemplate(), "local seed", "API");
        templates.publish(app, ws, evidenceTemplate(), "local seed", "API");

        Map<String, Persona> personas = new LinkedHashMap<>();
        personas.put("alice", persona(t, "u_alice", List.of(), List.of("site:A"), "第一步候选（角色 approver）· review-queue 组员 · 令牌带 site:A"));
        personas.put("bob", persona(t, "u_bob", List.of(), List.of(), "提交人"));
        personas.put("dave", persona(t, "u_dave", List.of(), List.of(), "finance 组员（会签）"));
        personas.put("erin", persona(t, "u_erin", List.of(), List.of(), "finance 组员（会签）"));
        personas.put("frank", persona(t, "u_frank", List.of(), List.of(), "review-queue 组员，领着一条任务"));
        personas.put("viewer", persona(t, "u_viewer", List.of("flow:todo:read", "flow:approval:read", "flow:ticket:read"), List.of(), "只读（S5）"));
        personas.put("auditor", persona(t, "u_auditor", List.of(), List.of(), "approval:view:all"));
        personas.put("admin", persona(t, "u_admin", List.of(), List.of(), "process:manage：发布 / 停用审批模板（S4）"));

        Actor bob = actor(t, personas.get("bob"), Set.of());
        Actor alice = actor(t, personas.get("alice"), Set.of("review-queue"));
        Map<String, Object> fx = new LinkedHashMap<>();
        fx.put("approvalTwoStep", submit(app, bob, "contract_approval", "C-1001", "合同 C-1001 · 华东区代理", Map.of("needsCosign", true), Set.of()));
        fx.put("approvalMine", submit(app, alice, "contract_approval", "C-1002", "合同 C-1002 · 自己提交", Map.of("needsCosign", false), Set.of()));
        fx.put("approvalHidden", submit(app, bob, "contract_approval", "C-1003", "合同 C-1003 · site:B", Map.of("needsCosign", false), Set.of("site:B")));
        for (int i = 1; i <= 3; i++) {
            submit(app, bob, "evidence_review", "E-10" + i, "证据包 E-10" + i, Map.of(), Set.of());
        }
        List<QueueTask> queue = queries.tasks(app, new TaskQuery("review-queue", List.of("OPEN"), null, null, 1, 50)).list();
        Actor frank = actor(t, personas.get("frank"), Set.of("review-queue"));
        tasks.claim(frank, queue.get(0).taskNo());
        fx.put("queueGroup", "review-queue");
        fx.put("queueTasks", queue.stream().map(QueueTask::taskNo).toList());
        fx.put("claimedByFrank", queue.get(0).taskNo());
        fx.put("claimTimeoutSec", 120);

        manifest = new Manifest(account.value(), ws.value(), TENANT, "ext:" + TENANT, personas, fx);
        log.info("本地联调种子已写入 account={} workspace={} tenant={}", account.value(), ws.value(), TENANT);
        return manifest;
    }

    /** 提交一张单并返回 {instanceNo, approvalNo}。 */
    private Map<String, String> submit(Actor app, Actor requester, String process, String ref, String title, Map<String, Object> fields,
            Set<String> scopes) {
        String no = instances.start(requester, new StartRequest(process, new SubjectRef(process.startsWith("contract") ? "contract" : "evidence", ref,
                Map.of("title", title)), fields, Map.of(), scopes)).instanceNo();
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
