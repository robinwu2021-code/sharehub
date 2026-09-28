package ai.neargo.opskit.ticket.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupView;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.SlaMetric;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.store.TicketMigrations;
import ai.neargo.opskit.ticket.store.TicketStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 21/07 O3-1：类型与选项集的发布、校验（引用不存在的流程 → 422）、幂等、模板继承、启停、版本。 */
class TicketDefinitionAppServiceTest {

    static final AccountId ACCOUNT = new AccountId("ac_t");
    static final WorkspaceId WS = new WorkspaceId("ws_t");
    static final TenantContext T = new TenantContext(ACCOUNT, WS);

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    /** 只实现类型校验要用的两个查询。 */
    static final class FakeProcesses implements ProcessClient {
        @Override
        public Optional<ProcessDef> processDef(TenantContext t, String code) {
            if (!"desk".equals(code)) {
                return Optional.empty();
            }
            return Optional.of(new ProcessDef("desk", t("服务台"), "new", List.of(new StatusDef("new", t("新"), StatusCategory.NEW, null),
                    new StatusDef("merged", t("已合并"), StatusCategory.CANCELLED, null)),
                    List.of(new TransitionDef("merge", t("合并"), Set.of("new"), "merged", null, null, null, null, true)), Set.of()));
        }

        @Override
        public boolean slaPolicyExists(TenantContext t, String code) {
            return "desk_sla".equals(code);
        }

        @Override
        public InstanceView start(Actor a, StartRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InstanceView transition(Actor a, String n, String c, TransitionRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InstanceView get(Actor a, String n) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ai.neargo.opskit.process.api.model.ActionView> actions(Actor a, String n) {
            return List.of();
        }

        @Override
        public Optional<InstanceView> snapshot(TenantContext t, String n) {
            return Optional.empty();
        }

        @Override
        public InstanceView updateFields(Actor a, String n, Map<String, Object> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceScopes(Actor a, String n, Collection<String> tags) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceParticipants(Actor a, String n, String role, Collection<String> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fireSystem(TenantContext t, String n, String code, String by, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SlaMetric> slaMetrics(TenantContext t, String n) {
            return List.of();
        }

        @Override
        public List<HistoryEntry> history(TenantContext t, String n) {
            return List.of();
        }
    }

    static final DirectoryClient DIRECTORY = new DirectoryClient() {
        @Override
        public Optional<GroupView> findGroup(TenantContext t, String code) {
            return "field-ops".equals(code) ? Optional.of(new GroupView(WS.value(), code, Map.of(), GroupKind.QUEUE, 600, null, DirectorySource.SYNC))
                    : Optional.empty();
        }

        @Override
        public List<String> membersOf(TenantContext t, String g) {
            return List.of();
        }

        @Override
        public List<String> membersWithAttr(TenantContext t, String key, Collection<String> values, int limit) {
            return List.of();
        }

        @Override
        public Map<String, String> displayNames(TenantContext t, Collection<String> ids) {
            return Map.of();
        }
    };

    static Actor admin() {
        return new Actor(T, Optional.of(PrincipalRef.member("mb_admin")), Optional.empty(), Set.of(), Set.of(), Set.of(), Set.of("ticket-type:manage"), Set.of());
    }

    static TicketDefinitionAppService service(TestDatabase db) {
        TicketMigrations.migrate(db.dataSource());
        return new TicketDefinitionAppService(TicketStore.create(db.dataSource()).definitions(), new FakeProcesses(), DIRECTORY,
                (actor, ws, action, target, detail) -> { }, Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ZoneOffset.UTC),
                new TransactionTemplate(new DataSourceTransactionManager(db.dataSource())));
    }

    static TicketTypeDef repair(String processCode, Options siteOptions) {
        return new TicketTypeDef("repair", t("报修"), processCode, List.of(
                new FieldDef("site", t("站点"), FieldType.SELECT, true, true, true, siteOptions, false, null),
                new FieldDef("device", t("设备"), FieldType.REF, true, true, true, null, false, "device"),
                new FieldDef("fixer", t("维修员"), FieldType.MEMBER, false, true, true, null, false, null)),
                Options.of(List.of(new OptionDef("P1", t("紧急"), true), new OptionDef("P2", t("普通"), true))), null, "RP",
                new AssignmentDef(AssignmentDef.Strategy.QUEUE, "field-ops", null, null, null), List.of("desk_sla"),
                List.of(new TicketTypeDef.ScopeFrom("site", "site")), new TicketTypeDef.Dedupe(List.of("device")), "merge");
    }

    static List<String> paths(Throwable ex) {
        assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
        return ((OpsKitException) ex).fieldErrors().stream().map(FieldError::path).toList();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void publishesIdempotentlyAndResolvesOptionSetReferences(TestDatabase db) {
        TicketDefinitionAppService s = service(db);
        s.publishOptionSet(admin(), WS, new OptionSetDef("sites", t("站点"), List.of(new OptionDef("S1", t("一号店"), true))), null, "API");
        TicketDefinitionAppService.Published first = s.publishType(admin(), WS, repair("desk", Options.ref("sites")), "首版", "CONSOLE");
        assertThat(first.created()).isTrue();
        assertThat(s.publishType(admin(), WS, repair("desk", Options.ref("sites")), "再点一次", "CONSOLE").created()).isFalse();

        TicketDefinitionAppService.ResolvedType r = s.resolveType(T, "repair").orElseThrow();
        assertThat(r.version()).isEqualTo(first.version());
        assertThat(r.def()).isEqualTo(repair("desk", Options.ref("sites")));
        assertThat(s.optionsOf(T, r.def().field("site").orElseThrow().options())).extracting(OptionDef::code).containsExactly("S1");
        assertThat(s.typeById(T, r.definitionId())).isEqualTo(r.def());
        assertThat(s.versions(T, TicketDefinitionAppService.TYPE, "repair")).singleElement().satisfies(v -> assertThat(v.note()).isEqualTo("首版"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void referencesThatDoNotExistAreRejectedWithFieldPaths(TestDatabase db) {
        TicketDefinitionAppService s = service(db);
        TicketTypeDef bad = new TicketTypeDef("repair", t("报修"), "nope", List.of(
                new FieldDef("site", t("站点"), FieldType.SELECT, true, true, true, Options.ref("missing"), false, null),
                new FieldDef("title", t("撞保留字"), FieldType.TEXT, false, true, false, null, false, null),
                new FieldDef("note", t("备注"), FieldType.LONG_TEXT, true, false, true, null, false, null),
                new FieldDef("who", t("多人"), FieldType.MEMBER, false, true, false, null, true, null)),
                null, null, "rp", new AssignmentDef(AssignmentDef.Strategy.FIELD, null, "who", null, null), List.of("ghost_sla"),
                List.of(new TicketTypeDef.ScopeFrom("nowhere", "site")), null, "merge");
        assertThatThrownBy(() -> s.publishType(admin(), WS, bad, null, "API")).satisfies(ex -> assertThat(paths(ex)).containsExactly(
                "processCode", "fields[0].options.optionSet", "fields[1].code", "fields[2].onCreate", "fields[2].filterable", "numberPrefix",
                "assignment.field", "slaPolicies[0]", "scopeFrom[0].field"));
        assertThat(s.listTypes(T)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void aWorkspaceInheritsTheAccountTemplateUntilItOverrides(TestDatabase db) {
        TicketDefinitionAppService s = service(db);
        Options inline = Options.of(List.of(new OptionDef("S1", t("一号店"), true)));
        s.publishType(admin(), WorkspaceId.TEMPLATE, repair("desk", inline), null, "API");
        assertThat(s.resolveType(T, "repair")).hasValueSatisfying(r -> assertThat(r.inherited()).isTrue());

        TicketTypeDef own = new TicketTypeDef("repair", t("报修（本店）"), "desk", repair("desk", inline).fields(), null, null, null, null, List.of(), List.of(),
                null, null);
        s.publishType(admin(), WS, own, null, "API");
        assertThat(s.resolveType(T, "repair")).hasValueSatisfying(r -> {
            assertThat(r.inherited()).isFalse();
            assertThat(r.def().name().resolve("zh", "en")).isEqualTo("报修（本店）");
        });
        assertThat(s.listTypes(T)).singleElement();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void disablingAndPermission(TestDatabase db) {
        TicketDefinitionAppService s = service(db);
        s.publishType(admin(), WS, repair("desk", Options.of(List.of(new OptionDef("S1", t("一号店"), true)))), null, "API");
        s.setTypeEnabled(admin(), WS, "repair", false);
        assertThat(s.resolveType(T, "repair")).hasValueSatisfying(r -> assertThat(r.enabled()).isFalse());
        Actor nobody = new Actor(T, Optional.of(PrincipalRef.member("mb_x")), Optional.empty(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> s.publishType(nobody, WS, repair("desk", Options.ref("x")), null, "API"))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.PERMISSION_DENIED));
    }
}
