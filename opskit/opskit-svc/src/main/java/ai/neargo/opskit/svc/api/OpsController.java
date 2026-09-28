package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.api.app.AppSecrets;
import ai.neargo.opskit.platform.api.app.AppView;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.app.AuditTrail;
import ai.neargo.opskit.platform.core.app.DeliveryAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.platform.core.auth.OperatorAuth;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户级运维面（21/06 §账户）：只认运维凭证 {@code opk_…}（启动引导 operators 段声明）。开空间 · 签发 / 轮换应用凭证 · 查审计 ·
 * 跨空间查投递与重投。{@code {a}} 为账户号或 {@code self}。写操作支持 Idempotency-Key。
 */
@RestController
@RequestMapping({"/v1/accounts/{a}", "/api/opskit/v1/accounts/{a}"})
public class OpsController {

    private final TenancyAppService tenancy;
    private final AppAppService apps;
    private final AuditTrail audit;
    private final DeliveryAppService deliveries;
    private final ai.neargo.opskit.platform.core.app.OperatorAppService operators;

    public OpsController(TenancyAppService tenancy, AppAppService apps, AuditTrail audit, DeliveryAppService deliveries,
            ai.neargo.opskit.platform.core.app.OperatorAppService operators) {
        this.operators = operators;
        this.tenancy = tenancy;
        this.apps = apps;
        this.audit = audit;
        this.deliveries = deliveries;
    }

    @GetMapping("/workspaces")
    public List<WorkspaceView> workspaces(OperatorAuth.Operator op) {
        return tenancy.listWorkspaces(op.account());
    }

    /** 开空间：externalRef = 宿主里的租户号（令牌 workspace 声明须与它一致），账户内唯一。 */
    @PostMapping("/workspaces")
    public WorkspaceView createWorkspace(OperatorAuth.Operator op, @RequestBody CreateWorkspaceRequest body) {
        return operators.createWorkspace(op.account(), body, "ops:" + op.code());
    }

    @GetMapping("/apps")
    public List<AppView> apps(OperatorAuth.Operator op) {
        return apps.list(op.account());
    }

    /** 签发应用凭证：凭证与签名密钥只在这次响应里出现一次，服务端只存哈希与密文。 */
    @PostMapping("/apps")
    public AppSecrets createApp(OperatorAuth.Operator op, @RequestBody CreateAppRequest body) {
        return apps.createApp(op.account(), body);
    }

    /** 轮换：旧凭证与旧签名密钥立即失效。 */
    @PostMapping("/apps/{id}/rotate-credential")
    public AppSecrets rotate(OperatorAuth.Operator op, @PathVariable("id") String appId) {
        return apps.rotate(op.account(), appId);
    }

    /** @param workspaceId 空 = 全部；{@code ""} = 只看账户级操作。时间窗默认最近 30 天、最长 366 天 */
    public record AuditQuery(String workspaceId, String action, Long fromMs, Long toMs, Integer page, Integer size) {
    }

    /**
     * 审计行。单独定义而不直接返回存储层的 Entry：springdoc 按类的简单名生成 schema，与实例历史的 Entry 撞名会被互相覆盖。
     *
     * @param detail 操作细节（JSON 字符串）
     */
    public record AuditRow(String workspaceId, String actor, String action, String target, String detail, long atMs) {
    }

    @PostMapping("/audit/query")
    public Page<AuditRow> auditQuery(OperatorAuth.Operator op, @RequestBody(required = false) AuditQuery q) {
        AuditQuery x = q == null ? new AuditQuery(null, null, null, null, null, null) : q;
        return audit.query(op.account(), x.workspaceId(), x.action(), x.fromMs(), x.toMs(), PageRequest.of(x.page(), x.size()))
                .map(e -> new AuditRow(e.workspaceId(), e.actor(), e.action(), e.target(), e.detail(), e.atMs()));
    }

    public record DeliveryQuery(String workspaceId, String state, String eventType, Integer page, Integer size) {
    }

    @PostMapping("/webhook-deliveries/query")
    public Page<DeliveryAppService.EventRow> deliveryQuery(OperatorAuth.Operator op, @RequestBody(required = false) DeliveryQuery q) {
        DeliveryQuery x = q == null ? new DeliveryQuery(null, null, null, null, null) : q;
        return deliveries.queryAccount(op.account(), x.workspaceId(), x.state(), x.eventType(), PageRequest.of(x.page(), x.size()));
    }

    @PostMapping("/webhook-deliveries/{id}/redeliver")
    public DeliveryAppService.EventRow redeliver(OperatorAuth.Operator op, @PathVariable("id") long id) {
        return deliveries.redeliverAccount(op.account(), id, "ops:" + op.code());
    }
}
