package ai.neargo.opskit.ticket.core.app;

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
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.core.domain.TicketTypeValidator;
import ai.neargo.opskit.ticket.store.TicketDefCodec;
import ai.neargo.opskit.ticket.store.dao.DefinitionDao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 工单类型与选项集（21/07 O3-1）：幂等发布（内容哈希即版本）· 账户模板 + 空间覆盖 · 启停 · 版本历史。
 * 已建的工单绑定建单时的类型版本（{@code type_definition_id}），类型改了只对新单生效。
 */
public class TicketDefinitionAppService {

    public static final String TYPE = "TICKET_TYPE";
    public static final String OPTION_SET = "OPTION_SET";

    public record Published(String code, String version, boolean created) {
    }

    /** @param definitionId 工单绑定的定义行；@param inherited 来自账户模板 */
    public record ResolvedType(long definitionId, String version, TicketTypeDef def, boolean inherited, boolean enabled) {
    }

    public record Version(String version, long atMs, String channel, String by, String note, boolean current) {
    }

    private final DefinitionDao definitions;
    private final ProcessClient processes;
    private final DirectoryClient directory;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionOperations tx;
    private final Map<Long, TicketTypeDef> byId = new ConcurrentHashMap<>();

    public TicketDefinitionAppService(DefinitionDao definitions, ProcessClient processes, DirectoryClient directory, AuditRecorder audit, Clock clock,
            TransactionOperations tx) {
        this.definitions = definitions;
        this.processes = processes;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    @RequiresPermission("ticket-type:manage")
    public Published publishType(Actor actor, WorkspaceId target, TicketTypeDef def, String note, String channel) {
        PermissionGuard.require(actor, "ticket-type:manage");
        requireOwnOrTemplate(actor, target);
        TenantContext t = new TenantContext(actor.tenant().accountId(), target.isTemplate() ? actor.tenant().workspaceId() : target);
        List<FieldError> errors = TicketTypeValidator.validate(def, lookup(t));
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid ticket type", Map.of("code", String.valueOf(def.code())), errors);
        }
        return publish(actor, target, TYPE, def.code(), TicketDefCodec.toCanonicalJson(def), TicketDefCodec.textJson(def.name()), note, channel);
    }

    @RequiresPermission("ticket-type:manage")
    public Published publishOptionSet(Actor actor, WorkspaceId target, OptionSetDef def, String note, String channel) {
        PermissionGuard.require(actor, "ticket-type:manage");
        requireOwnOrTemplate(actor, target);
        List<FieldError> errors = TicketTypeValidator.validate(def);
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid option set", Map.of("code", String.valueOf(def.code())), errors);
        }
        return publish(actor, target, OPTION_SET, def.code(), TicketDefCodec.toCanonicalJson(def), TicketDefCodec.textJson(def.name()), note, channel);
    }

    /** 当前版本：空间自己的优先，其次账户模板。 */
    @SystemAction("读：工单类型是配置，空间成员可读；也供建单内部调用")
    public Optional<ResolvedType> resolveType(TenantContext t, String code) {
        return visible(t, TYPE, code).map(r -> new ResolvedType(r.id(), r.version(), type(r), r.workspaceId().isEmpty(),
                definitions.enabled(t.accountId(), t.workspaceId(), TYPE, code)));
    }

    /** 工单绑定的那一版（类型改了，在途工单仍按建单时的字段定义校验）。 */
    @SystemAction("工单读写内部调用")
    public TicketTypeDef typeById(TenantContext t, long definitionId) {
        TicketTypeDef hit = byId.get(definitionId);
        if (hit != null) {
            return hit;
        }
        return type(definitions.byId(t.accountId(), definitionId).orElseThrow(() -> new IllegalStateException("ticket type row vanished: " + definitionId)));
    }

    @SystemAction("读：同 resolveType")
    public Optional<OptionSetDef> optionSet(TenantContext t, String code) {
        return visible(t, OPTION_SET, code).map(r -> TicketDefCodec.optionSetFromJson(r.body()));
    }

    /** 选项来源 → 选项列表（引用选项集时取其当前版本）。 */
    @SystemAction("工单读写内部调用")
    public List<OptionDef> optionsOf(TenantContext t, Options o) {
        if (o == null) {
            return List.of();
        }
        return o.optionSet() == null ? o.inline() : optionSet(t, o.optionSet()).map(OptionSetDef::options).orElse(List.of());
    }

    /** 字段 → 可选项（给字段校验用）。 */
    @SystemAction("工单读写内部调用")
    public Function<FieldDef, List<OptionDef>> fieldOptions(TenantContext t) {
        Map<String, List<OptionDef>> cache = new LinkedHashMap<>();
        return f -> f.options() == null ? List.of()
                : cache.computeIfAbsent(f.code(), k -> optionsOf(t, f.options()));
    }

    @SystemAction("读：同 resolveType")
    public List<ResolvedType> listTypes(TenantContext t) {
        Map<String, ResolvedType> out = new LinkedHashMap<>();
        for (DefinitionDao.Row r : definitions.latestVisible(t.accountId(), t.workspaceId(), TYPE)) {
            out.putIfAbsent(r.code(), new ResolvedType(r.id(), r.version(), type(r), r.workspaceId().isEmpty(),
                    definitions.enabled(t.accountId(), t.workspaceId(), TYPE, r.code())));
        }
        return List.copyOf(out.values());
    }

    @SystemAction("读：同 resolveType")
    public List<OptionSetDef> listOptionSets(TenantContext t) {
        Map<String, OptionSetDef> out = new LinkedHashMap<>();
        for (DefinitionDao.Row r : definitions.latestVisible(t.accountId(), t.workspaceId(), OPTION_SET)) {
            out.putIfAbsent(r.code(), TicketDefCodec.optionSetFromJson(r.body()));
        }
        return List.copyOf(out.values());
    }

    @SystemAction("读：同 resolveType")
    public List<Version> versions(TenantContext t, String kind, String code) {
        List<DefinitionDao.Row> rows = definitions.versions(t.accountId(), t.workspaceId(), kind, code);
        if (rows.isEmpty()) {
            rows = definitions.versions(t.accountId(), WorkspaceId.TEMPLATE, kind, code);
        }
        return rows.stream().map(r -> new Version(r.version(), r.publishedAtMs(), r.channel(), r.publishedBy(), r.note(), r.latest())).toList();
    }

    /** 停用 = 不接受新建单；在途工单照常流转。 */
    @RequiresPermission("ticket-type:manage")
    public void setTypeEnabled(Actor actor, WorkspaceId target, String code, boolean enabled) {
        PermissionGuard.require(actor, "ticket-type:manage");
        requireOwnOrTemplate(actor, target);
        if (visible(actor.tenant(), TYPE, code).isEmpty()) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "ticket type not found");
        }
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        tx.executeWithoutResult(s -> {
            definitions.setEnabled(actor.tenant().accountId(), target, TYPE, code, enabled, by, clock.millis());
            audit.record(actor, target.value(), enabled ? "definition.enabled" : "definition.disabled", "ticket_type:" + code, Map.of());
        });
    }

    // ── 内部 ────────────────────────────────────────────────

    private Published publish(Actor actor, WorkspaceId target, String kind, String code, String json, String nameJson, String note, String channel) {
        String version = version(json);
        String ch = channel == null ? "API" : channel;
        String by = actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?"));
        return tx.execute(s -> {
            var account = actor.tenant().accountId();
            Optional<DefinitionDao.Row> current = definitions.latest(account, target, kind, code);
            if (current.isPresent() && current.get().version().equals(version)) {
                return new Published(code, version, false);
            }
            definitions.insertIfAbsent(account, target, kind, code, version, nameJson, json, ch, note, by, clock.millis());
            definitions.markLatest(account, target, kind, code, version);
            audit.record(actor, target.value(), "definition.published", kind.toLowerCase(java.util.Locale.ROOT) + ":" + code,
                    Map.of("version", version, "channel", ch));
            return new Published(code, version, true);
        });
    }

    private TicketTypeValidator.Lookup lookup(TenantContext t) {
        return new TicketTypeValidator.Lookup() {
            @Override
            public Optional<ProcessDef> process(String code) {
                return processes.processDef(t, code);
            }

            @Override
            public Optional<OptionSetDef> optionSet(String code) {
                return TicketDefinitionAppService.this.optionSet(t, code);
            }

            @Override
            public boolean slaPolicy(String code) {
                return processes.slaPolicyExists(t, code);
            }

            @Override
            public boolean group(String code) {
                return directory.findGroup(t, code).isPresent();
            }
        };
    }

    private Optional<DefinitionDao.Row> visible(TenantContext t, String kind, String code) {
        return definitions.latest(t.accountId(), t.workspaceId(), kind, code).or(() -> definitions.latest(t.accountId(), WorkspaceId.TEMPLATE, kind, code));
    }

    private TicketTypeDef type(DefinitionDao.Row r) {
        return byId.computeIfAbsent(r.id(), id -> TicketDefCodec.typeFromJson(r.body()));
    }

    private static void requireOwnOrTemplate(Actor actor, WorkspaceId target) {
        if (!target.isTemplate() && !target.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
    }

    /** 规范化 JSON 的 SHA-256 前 16 位（与流程定义同一口径）。 */
    private static String version(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
