package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.PermissionGuard;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.core.domain.definition.CompiledProcess;
import ai.neargo.opskit.process.core.domain.definition.DefinitionVersion;
import ai.neargo.opskit.process.core.domain.definition.ProcessValidator;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import ai.neargo.opskit.process.store.dao.DefinitionDao;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 流程定义的发布与解析（21/07 O2-1）。
 *
 * <p>发布：校验（全部错误一次返回）→ 规范化 → 内容哈希为版本 → 内容没变不产生新版本 → 切为当前版本 → 审计，同一事务。
 * 解析：空间自己的当前版本优先，其次账户模板；在途实例绑定具体定义行，模板改动不影响它们。
 */
public class ProcessDefinitionAppService {

    public static final String KIND = "PROCESS";
    private static final int CACHE_LIMIT = 5000;

    public record PublishResult(String code, String version, boolean created) {
    }

    /** @param enabled 停用 = 不接受新实例，在途照常走完 */
    public record Resolved(CompiledProcess process, boolean enabled, boolean template) {
    }

    private final DefinitionDao definitions;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionOperations tx;
    private final Map<Long, CompiledProcess> compiled = new ConcurrentHashMap<>();

    public ProcessDefinitionAppService(DefinitionDao definitions, AuditRecorder audit, Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /** @param target 空间，或 {@link WorkspaceId#TEMPLATE}（账户模板，账户下全部空间可用） */
    @RequiresPermission("process:manage")
    public PublishResult publish(Actor actor, WorkspaceId target, ProcessDef def, String note, String channel) {
        PermissionGuard.require(actor, "process:manage");
        requireOwnOrTemplate(actor, target);
        List<FieldError> errors = ProcessValidator.validate(def);
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid process definition", Map.of("code", String.valueOf(def.code())), errors);
        }
        String json = ProcessDefCodec.toCanonicalJson(def);
        String version = DefinitionVersion.of(json);
        String name = ProcessDefCodec.textJson(def.name());
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        return tx.execute(s -> {
            Optional<DefinitionDao.Row> current = definitions.latest(actor.tenant().accountId(), target, KIND, def.code());
            if (current.isPresent() && current.get().version().equals(version)) {
                return new PublishResult(def.code(), version, false);
            }
            definitions.insertIfAbsent(actor.tenant().accountId(), target, KIND, def.code(), version, name, json,
                    channel == null ? "API" : channel, note, by, clock.millis());
            definitions.markLatest(actor.tenant().accountId(), target, KIND, def.code(), version);
            audit.record(actor, target.value(), "definition.published", "process:" + def.code(),
                    Map.of("version", version, "channel", channel == null ? "API" : channel));
            return new PublishResult(def.code(), version, true);
        });
    }

    @SystemAction("实例启动 / 迁移内部调用")
    public Resolved resolve(TenantContext t, String code) {
        DefinitionDao.Row row = definitions.latest(t.accountId(), t.workspaceId(), KIND, code)
                .or(() -> definitions.latest(t.accountId(), WorkspaceId.TEMPLATE, KIND, code))
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "process not found: " + code));
        return new Resolved(compile(row), definitions.enabled(t.accountId(), t.workspaceId(), KIND, code), row.workspaceId().isEmpty());
    }

    /** 在途实例用：按它绑定的定义行取，不看当前版本。 */
    @SystemAction("实例迁移内部调用")
    public CompiledProcess byDefinitionId(TenantContext t, long definitionId) {
        CompiledProcess hit = compiled.get(definitionId);
        if (hit != null) {
            return hit;
        }
        return compile(definitions.byId(t.accountId(), definitionId)
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "definition not found")));
    }

    @RequiresPermission("process:manage")
    public void setEnabled(Actor actor, WorkspaceId target, String code, boolean enabled) {
        PermissionGuard.require(actor, "process:manage");
        requireOwnOrTemplate(actor, target);
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        tx.executeWithoutResult(s -> {
            definitions.setEnabled(actor.tenant().accountId(), target, KIND, code, enabled, by, clock.millis());
            audit.record(actor, target.value(), enabled ? "definition.enabled" : "definition.disabled", "process:" + code, Map.of());
        });
    }

    @SystemAction("读：定义版本历史")
    public List<DefinitionDao.Row> versions(TenantContext t, WorkspaceId target, String code) {
        return definitions.versions(t.accountId(), target, KIND, code);
    }

    private CompiledProcess compile(DefinitionDao.Row row) {
        if (compiled.size() > CACHE_LIMIT) {
            compiled.clear();
        }
        return compiled.computeIfAbsent(row.id(), id -> new CompiledProcess(id, row.version(), ProcessDefCodec.fromJson(row.body())));
    }

    private static void requireOwnOrTemplate(Actor actor, WorkspaceId target) {
        if (!target.isTemplate() && !target.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
    }
}
