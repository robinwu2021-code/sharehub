package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate.Approver;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate.Step;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate.When;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.domain.definition.SampleProcesses;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-11：模板校验（路径按模板字段）· 编译 · 幂等发布 · 产物能跑 · 在途旧版本计数 · 停用 · 手工流程不被覆盖。 */
class ApprovalTemplateAppServiceTest {

    record Env(ProcessFixture f, ApprovalTemplateAppService templates, ProcessDefinitionAppService defs, InstanceAppService instances,
            ApprovalAppService approvals) {
        Actor member(String id, Set<String> roles, Set<String> groups) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), groups, roles, Set.of(), Set.of(), Set.of());
        }

        String submit(String code, Map<String, Object> fields) {
            Actor bd = member("mb_bd", Set.of(), Set.of());
            String no = instances.start(bd, new StartRequest(code, null, fields, Map.of(), Set.of())).instanceNo();
            instances.transition(bd, no, "submit", TransitionRequest.empty());
            return no;
        }

        String openTask(String instanceNo) {
            return f.jdbc.sql("SELECT task_no FROM proc_task WHERE instance_no = ? AND status IN ('OPEN','CLAIMED','ASSIGNED') ORDER BY task_no")
                    .param(instanceNo).query(String.class).list().get(0);
        }

        String approvalOf(String instanceNo) {
            return f.jdbc.sql("SELECT approval_no FROM proc_approval WHERE instance_no = ?").param(instanceNo).query(String.class).single();
        }
    }

    private static Env env(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return "finance".equals(g) ? List.of("mb_f1") : List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return Optional.empty();
            }
        };
        BizNoGenerator ids = new BizNoGenerator(f.clock);
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, ev -> { }, ids, f.clock, f.tx);
        AtomicReference<InstanceAppService> ref = new AtomicReference<>();
        ApprovalAppService approvals = new ApprovalAppService(defs, f.store.approvals(), f.store.tasks(), f.store.instances(), f.store.participants(),
                f.store.logs(), tasks, ref::get, dir, ev -> { }, ids, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, approvals), Optional.of(tasks), ev -> { }, ids, f.clock, f.tx);
        ref.set(inst);
        return new Env(f, new ApprovalTemplateAppService(f.store.definitions(), defs, f.store.queries(), f.audit, f.clock, f.tx), defs, inst, approvals);
    }

    static LocalizedText t(String zh) {
        return LocalizedText.of("zh", zh);
    }

    /** 运营审批（角色，或签）→ 财务会签（组，仅当 needsCosign 为真）。 */
    static ApprovalTemplate contract() {
        return new ApprovalTemplate("contract_tpl", t("合同审批"), List.of(
                new Step("ops", t("运营审批"), new Approver(ApproverKind.ROLE, List.of("approver")), "ANY", null, 24),
                new Step("finance", t("财务会签"), new Approver(ApproverKind.GROUP, List.of("finance")), "ALL", new When("needsCosign", "TRUE", null), null)),
                ApprovalTemplate.Rules.DEFAULT);
    }

    private static List<String> paths(Throwable ex) {
        assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
        return ((OpsKitException) ex).fieldErrors().stream().map(FieldError::path).toList();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void publishingTheSameTemplateTwiceIsIdempotentAndTheProductRuns(TestDatabase db) {
        Env e = env(db);
        ApprovalTemplateAppService.Published first = e.templates.publish(e.f.system(), e.f.workspace, contract(), "首版", "CONSOLE");
        ApprovalTemplateAppService.Published again = e.templates.publish(e.f.system(), e.f.workspace, contract(), "重复点了发布", "CONSOLE");
        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(e.templates.versions(e.f.tenant, "contract_tpl")).singleElement().satisfies(v -> assertThat(v.current()).isTrue());
        assertThat(e.defs.versions(e.f.tenant, e.f.workspace, "contract_tpl")).as("产物也没多出版本").singleElement()
                .satisfies(r -> assertThat(r.note()).isEqualTo("template:" + first.version()));

        String no = e.submit("contract_tpl", Map.of("needsCosign", true));
        e.approvals.decide(e.member("mb_ops", Set.of("approver"), Set.of()), e.approvalOf(no), e.openTask(no), true, null);
        e.approvals.decide(e.member("mb_f1", Set.of(), Set.of("finance")), e.approvalOf(no), e.openTask(no), true, null);
        assertThat(e.instances.get(e.f.system(), no).status()).as("两步都过 → 已通过").isEqualTo("approved");

        assertThat(e.templates.get(e.f.tenant, "contract_tpl")).contains(contract());
        assertThat(e.templates.list(e.f.tenant)).singleElement().satisfies(s -> {
            assertThat(s.version()).isEqualTo(first.version());
            assertThat(s.enabled()).isTrue();
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void errorsPointAtTemplateFields(TestDatabase db) {
        Env e = env(db);
        ApprovalTemplate bad = new ApprovalTemplate("contract_tpl", new LocalizedText(Map.of("zh", " ")), List.of(
                new Step("ops", t("运营"), new Approver(ApproverKind.ROLE, List.of("approver")), "ALL", null, null),
                new Step("pick", t("指定"), new Approver(ApproverKind.MEMBER, List.of()), "ANY", new When("region", "EQ", null), 0),
                new Step("ops", null, new Approver(ApproverKind.FIELD, List.of("a", "b")), "SOME", null, null)),
                ApprovalTemplate.Rules.DEFAULT);
        assertThatThrownBy(() -> e.templates.publish(e.f.system(), e.f.workspace, bad, null, "CONSOLE"))
                .satisfies(ex -> assertThat(paths(ex)).containsExactly("name", "steps[0].mode", "steps[1].approver.value", "steps[1].when.value",
                        "steps[1].dueHours", "steps[2].code", "steps[2].name", "steps[2].approver.value", "steps[2].mode"));
        assertThat(e.templates.list(e.f.tenant)).as("校验不过什么也不落").isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void aNewVersionReportsHowManyAreStillOnTheOldOne(TestDatabase db) {
        Env e = env(db);
        ApprovalTemplateAppService.Published v1 = e.templates.publish(e.f.system(), e.f.workspace, contract(), null, "CONSOLE");
        e.submit("contract_tpl", Map.of("needsCosign", false));
        ApprovalTemplate changed = new ApprovalTemplate("contract_tpl", t("合同审批（改）"), contract().steps(), new ApprovalTemplate.Rules(true, false));
        ApprovalTemplateAppService.Published v2 = e.templates.publish(e.f.system(), e.f.workspace, changed, "放开自审", "CONSOLE");

        assertThat(v2.created()).isTrue();
        assertThat(v2.version()).isNotEqualTo(v1.version());
        assertThat(v2.runningOnOlderVersions()).isEqualTo(1);
        assertThat(e.templates.versions(e.f.tenant, "contract_tpl")).extracting(ApprovalTemplateAppService.Version::current).containsExactly(true, false);
        assertThat(e.templates.list(e.f.tenant).get(0).running()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void disablingStopsNewSubmissions(TestDatabase db) {
        Env e = env(db);
        e.templates.publish(e.f.system(), e.f.workspace, contract(), null, "CONSOLE");
        e.templates.setEnabled(e.f.system(), e.f.workspace, "contract_tpl", false);
        assertThat(e.templates.list(e.f.tenant).get(0).enabled()).isFalse();
        assertThatThrownBy(() -> e.submit("contract_tpl", Map.of()))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(ProcessErrors.DEFINITION_DISABLED));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void aHandWrittenProcessIsNotOverwritten(TestDatabase db) {
        Env e = env(db);
        e.defs.publish(e.f.system(), e.f.workspace, SampleProcesses.contractApproval(), null, "API");
        ApprovalTemplate clash = new ApprovalTemplate("contract_approval", t("撞名"), contract().steps(), ApprovalTemplate.Rules.DEFAULT);
        assertThatThrownBy(() -> e.templates.publish(e.f.system(), e.f.workspace, clash, null, "CONSOLE"))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(ApprovalTemplateAppService.CODE_TAKEN));
        assertThat(e.templates.list(e.f.tenant)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void onlyProcessManagersPublish(TestDatabase db) {
        Env e = env(db);
        assertThatThrownBy(() -> e.templates.publish(e.member("mb_x", Set.of(), Set.of()), e.f.workspace, contract(), null, "CONSOLE"))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.PERMISSION_DENIED));
        List<String> none = new ArrayList<>(e.templates.list(e.f.tenant).stream().map(s -> s.template().code()).toList());
        assertThat(none).isEmpty();
    }
}
