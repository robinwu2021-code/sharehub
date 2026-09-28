package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.ErrorKey;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.PermissionGuard;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import ai.neargo.opskit.process.core.domain.definition.ApprovalTemplateCompiler;
import ai.neargo.opskit.process.core.domain.definition.DefinitionVersion;
import ai.neargo.opskit.process.store.ApprovalTemplateCodec;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import ai.neargo.opskit.process.store.dao.DefinitionDao;
import ai.neargo.opskit.process.store.dao.QueryDao;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 审批模板（21/07 O2-11 · 21/02 §6.4a）：存模板、校验（错误路径按模板字段）、同一事务里编译并发布同名的审批类流程。
 *
 * <p>版本：模板与产物各自是内容哈希。同一份模板 → 同一份产物 → 两边都不产生新版本（重复发布幂等）。
 * 产物的 note 记 {@code template:{模板版本}}，据此区分「模板编出来的」与「API 手工登记的」流程 —— 后者不许被模板覆盖。
 */
public class ApprovalTemplateAppService {

    public static final String KIND = "APPROVAL_TPL";
    public static final String COMPILED_NOTE_PREFIX = "template:";
    public static final ErrorKey CODE_TAKEN = new ErrorKey("opskit.template.code_taken", 409);

    /** @param runningOnOlderVersions 还按旧版本走的在途单数（新版本只对新提交的单生效） */
    public record Published(String code, String version, boolean created, long runningOnOlderVersions) {
    }

    /** @param inherited 来自账户模板（空间没有自己的版本） */
    public record Summary(ApprovalTemplate template, String version, long running, boolean enabled, boolean inherited) {
    }

    public record Version(String version, long atMs, String channel, String by, String note, boolean current) {
    }

    private final DefinitionDao definitions;
    private final ProcessDefinitionAppService processes;
    private final QueryDao queries;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionOperations tx;

    public ApprovalTemplateAppService(DefinitionDao definitions, ProcessDefinitionAppService processes, QueryDao queries, AuditRecorder audit,
            Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.processes = processes;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    @RequiresPermission("process:manage")
    public Published publish(Actor actor, WorkspaceId target, ApprovalTemplate t, String note, String channel) {
        PermissionGuard.require(actor, "process:manage");
        requireOwnOrTemplate(actor, target);
        List<FieldError> errors = ApprovalTemplateCompiler.validate(t);
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid approval template", Map.of("code", String.valueOf(t.code())), errors);
        }
        String json = ApprovalTemplateCodec.toCanonicalJson(t);
        String version = DefinitionVersion.of(json);
        String ch = channel == null ? "API" : channel;
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        return tx.execute(s -> {
            var account = actor.tenant().accountId();
            Optional<DefinitionDao.Row> handWritten = definitions.latest(account, target, ProcessDefinitionAppService.KIND, t.code())
                    .filter(p -> p.note() == null || !p.note().startsWith(COMPILED_NOTE_PREFIX));
            if (handWritten.isPresent()) {
                throw new OpsKitException(CODE_TAKEN, "a process with this code was registered directly; pick another template code",
                        Map.of("code", t.code()), List.of(new FieldError("code", CODE_TAKEN.key())));
            }
            Optional<DefinitionDao.Row> current = definitions.latest(account, target, KIND, t.code());
            boolean created = current.isEmpty() || !current.get().version().equals(version);
            if (created) {
                definitions.insertIfAbsent(account, target, KIND, t.code(), version, ProcessDefCodec.textJson(t.name()), json, ch, note, by, clock.millis());
                definitions.markLatest(account, target, KIND, t.code(), version);
                audit.record(actor, target.value(), "definition.published", "approval_template:" + t.code(), Map.of("version", version, "channel", ch));
            }
            processes.publish(actor, target, ApprovalTemplateCompiler.compile(t), COMPILED_NOTE_PREFIX + version, ch);
            long older = 0;
            if (!target.isTemplate()) {
                long latestId = definitions.latest(account, target, ProcessDefinitionAppService.KIND, t.code()).orElseThrow().id();
                older = queries.openInstances(target, t.code(), latestId);
            }
            return new Published(t.code(), version, created, older);
        });
    }

    /** 空间自己的版本优先，其次账户模板。 */
    @SystemAction("读：审批模板是配置，空间成员可读（控制台流程配置页、审批列表的流程筛选项）")
    public Optional<ApprovalTemplate> get(TenantContext t, String code) {
        return visible(t, code).map(r -> ApprovalTemplateCodec.fromJson(r.body()));
    }

    @SystemAction("读：同 get")
    public List<Summary> list(TenantContext t) {
        Map<String, DefinitionDao.Row> byCode = new LinkedHashMap<>();
        for (DefinitionDao.Row r : definitions.latestVisible(t.accountId(), t.workspaceId(), KIND)) {
            byCode.putIfAbsent(r.code(), r); // 查询已按「空间优先」排好
        }
        List<Summary> out = new ArrayList<>();
        for (DefinitionDao.Row r : byCode.values()) {
            out.add(new Summary(ApprovalTemplateCodec.fromJson(r.body()), r.version(), queries.openInstances(t.workspaceId(), r.code(), null),
                    definitions.enabled(t.accountId(), t.workspaceId(), ProcessDefinitionAppService.KIND, r.code()), r.workspaceId().isEmpty()));
        }
        return out;
    }

    @SystemAction("读：同 get")
    public List<Version> versions(TenantContext t, String code) {
        List<DefinitionDao.Row> rows = definitions.versions(t.accountId(), t.workspaceId(), KIND, code);
        if (rows.isEmpty()) {
            rows = definitions.versions(t.accountId(), WorkspaceId.TEMPLATE, KIND, code);
        }
        return rows.stream().map(r -> new Version(r.version(), r.publishedAtMs(), r.channel(), r.publishedBy(), r.note(), r.latest())).toList();
    }

    /** 停用 = 不接受新提交（停的是产物流程），在途单照常走完。 */
    @RequiresPermission("process:manage")
    public void setEnabled(Actor actor, WorkspaceId target, String code, boolean enabled) {
        PermissionGuard.require(actor, "process:manage");
        if (visible(actor.tenant(), code).isEmpty()) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "approval template not found");
        }
        processes.setEnabled(actor, target, code, enabled);
    }

    private Optional<DefinitionDao.Row> visible(TenantContext t, String code) {
        return definitions.latest(t.accountId(), t.workspaceId(), KIND, code).or(() -> definitions.latest(t.accountId(), WorkspaceId.TEMPLATE, KIND, code));
    }

    private static void requireOwnOrTemplate(Actor actor, WorkspaceId target) {
        if (!target.isTemplate() && !target.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
    }
}
