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
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.core.domain.definition.DefinitionVersion;
import ai.neargo.opskit.process.core.domain.definition.SlaPolicyValidator;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import ai.neargo.opskit.process.store.SlaPolicyCodec;
import ai.neargo.opskit.process.store.dao.DefinitionDao;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SLA 策略的登记（21/02 §6.5 · 21/06 §四）：幂等发布（内容哈希即版本）、按版本读取。
 * 已起算的计时器绑定起算时的策略版本；新版本只对之后挂上策略的实例生效。
 */
public class SlaPolicyAppService {

    public static final String KIND = "SLA_POLICY";

    public record Published(String code, String version, boolean created) {
    }

    /** @param def 解析后的策略；@param inherited 来自账户模板 */
    public record Resolved(SlaPolicyDef def, String version, boolean inherited) {
    }

    private final DefinitionDao definitions;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionOperations tx;
    private final Map<String, SlaPolicyDef> byVersion = new java.util.concurrent.ConcurrentHashMap<>();

    public SlaPolicyAppService(DefinitionDao definitions, AuditRecorder audit, Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    @RequiresPermission("sla:manage")
    public Published publish(Actor actor, WorkspaceId target, SlaPolicyDef def, String note, String channel) {
        PermissionGuard.require(actor, "sla:manage");
        if (!target.isTemplate() && !target.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        List<FieldError> errors = SlaPolicyValidator.validate(def);
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid sla policy", Map.of("code", String.valueOf(def.code())), errors);
        }
        String json = SlaPolicyCodec.toCanonicalJson(def);
        String version = DefinitionVersion.of(json);
        String ch = channel == null ? "API" : channel;
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        return tx.execute(s -> {
            var account = actor.tenant().accountId();
            Optional<DefinitionDao.Row> current = definitions.latest(account, target, KIND, def.code());
            if (current.isPresent() && current.get().version().equals(version)) {
                return new Published(def.code(), version, false);
            }
            definitions.insertIfAbsent(account, target, KIND, def.code(), version, ProcessDefCodec.textJson(def.name()), json, ch, note, by, clock.millis());
            definitions.markLatest(account, target, KIND, def.code(), version);
            audit.record(actor, target.value(), "definition.published", "sla_policy:" + def.code(), Map.of("version", version, "channel", ch));
            return new Published(def.code(), version, true);
        });
    }

    /** 当前版本：空间自己的优先，其次账户模板。 */
    @SystemAction("读：SLA 策略是配置；也供计时器起算内部调用")
    public Optional<Resolved> resolve(TenantContext t, String code) {
        return definitions.latest(t.accountId(), t.workspaceId(), KIND, code)
                .or(() -> definitions.latest(t.accountId(), WorkspaceId.TEMPLATE, KIND, code))
                .map(r -> new Resolved(SlaPolicyCodec.fromJson(r.body()), r.version(), r.workspaceId().isEmpty()));
    }

    /** 计时器绑定的那个版本（策略改了，在途计时器仍按起算时的规则走）。 */
    @SystemAction("计时器内部调用")
    public SlaPolicyDef atVersion(TenantContext t, String code, String version) {
        return byVersion.computeIfAbsent(t.accountId().value() + "|" + code + "|" + version, k ->
                definitions.byVersion(t.accountId(), t.workspaceId(), KIND, code, version)
                        .or(() -> definitions.byVersion(t.accountId(), WorkspaceId.TEMPLATE, KIND, code, version))
                        .map(r -> SlaPolicyCodec.fromJson(r.body()))
                        .orElseThrow(() -> new IllegalStateException("sla policy version vanished: " + code + "@" + version)));
    }

    @SystemAction("读：同 resolve")
    public List<Resolved> list(TenantContext t) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (DefinitionDao.Row r : definitions.latestVisible(t.accountId(), t.workspaceId(), KIND)) {
            out.putIfAbsent(r.code(), new Resolved(SlaPolicyCodec.fromJson(r.body()), r.version(), r.workspaceId().isEmpty()));
        }
        return List.copyOf(out.values());
    }
}
