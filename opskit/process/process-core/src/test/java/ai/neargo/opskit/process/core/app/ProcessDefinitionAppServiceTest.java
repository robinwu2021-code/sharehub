package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.core.domain.definition.SampleProcesses;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-1：内容哈希版本 · 内容不变不出新版本 · 在途按旧版本 · 模板与覆盖 · 停用 · 全部错误一次返回。 */
class ProcessDefinitionAppServiceTest {

    private static ProcessDefinitionAppService svc(ProcessFixture f) {
        return new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
    }

    private static ProcessDef renamed(ProcessDef d, String zh) {
        return new ProcessDef(d.code(), LocalizedText.of("zh", zh), d.initialStatus(), d.statuses(), d.transitions(), d.participantRoles());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void unchangedContentDoesNotCreateANewVersion(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService s = svc(f);
        ProcessDefinitionAppService.PublishResult first = s.publish(f.system(), f.workspace, SampleProcesses.contractApproval(), "首版", "API");
        ProcessDefinitionAppService.PublishResult again = s.publish(f.system(), f.workspace, SampleProcesses.contractApproval(), null, "API");

        assertThat(first.created()).isTrue();
        assertThat(first.version()).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(again.created()).isFalse();
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(s.versions(f.tenant, f.workspace, "contract_approval")).hasSize(1);
        assertThat(f.audits).hasSize(1).first().satisfies(a -> assertThat(a.action()).isEqualTo("definition.published"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void newVersionBecomesCurrentWhileRunningInstancesKeepTheirs(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService s = svc(f);
        s.publish(f.system(), f.workspace, SampleProcesses.contractApproval(), null, "API");
        ProcessDefinitionAppService.Resolved v1 = s.resolve(f.tenant, "contract_approval");

        ProcessDefinitionAppService.PublishResult r2 = s.publish(f.system(), f.workspace, renamed(SampleProcesses.contractApproval(), "合同审批（新）"), "改名", "CONSOLE");
        ProcessDefinitionAppService.Resolved current = s.resolve(f.tenant, "contract_approval");

        assertThat(current.process().version()).isEqualTo(r2.version()).isNotEqualTo(v1.process().version());
        assertThat(s.byDefinitionId(f.tenant, v1.process().definitionId()).version()).as("在途实例按启动时的定义行走完")
                .isEqualTo(v1.process().version());
        assertThat(s.versions(f.tenant, f.workspace, "contract_approval")).hasSize(2);
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM proc_definition WHERE latest = 1").query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceDefinitionOverridesTheAccountTemplate(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService s = svc(f);
        String tpl = s.publish(f.system(), WorkspaceId.TEMPLATE, SampleProcesses.contractApproval(), null, "API").version();
        assertThat(s.resolve(f.tenant, "contract_approval").template()).isTrue();
        assertThat(s.resolve(f.tenant, "contract_approval").process().version()).isEqualTo(tpl);

        String own = s.publish(f.system(), f.workspace, renamed(SampleProcesses.contractApproval(), "本空间的"), null, "API").version();
        ProcessDefinitionAppService.Resolved r = s.resolve(f.tenant, "contract_approval");
        assertThat(r.template()).isFalse();
        assertThat(r.process().version()).isEqualTo(own);

        assertThatThrownBy(() -> s.publish(f.inOtherWorkspace(), f.workspace, SampleProcesses.contractApproval(), null, "API"))
                .extracting(e -> ((OpsKitException) e).errorKey()).as("不能往别的空间发布").isEqualTo(CommonErrors.NOT_FOUND);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void disablingStopsNewInstancesOnly(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService s = svc(f);
        s.publish(f.system(), WorkspaceId.TEMPLATE, SampleProcesses.contractApproval(), null, "API");
        assertThat(s.resolve(f.tenant, "contract_approval").enabled()).isTrue();
        s.setEnabled(f.system(), f.workspace, "contract_approval", false);
        assertThat(s.resolve(f.tenant, "contract_approval").enabled()).isFalse();
        assertThat(s.resolve(f.tenant, "contract_approval").process()).as("定义仍可解析（在途实例要用）").isNotNull();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void invalidDefinitionIsRejectedWithEveryErrorAndNothingIsStored(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDef bad = new ProcessDef("contract_approval", null, "nope", SampleProcesses.contractApproval().statuses(),
                SampleProcesses.contractApproval().transitions(), Set.of());
        assertThatThrownBy(() -> svc(f).publish(f.system(), f.workspace, bad, null, "API"))
                .isInstanceOf(OpsKitException.class)
                .satisfies(e -> assertThat(((OpsKitException) e).fieldErrors()).extracting(FieldError::path).contains("initialStatus"));
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM proc_definition").query(Integer.class).single()).isZero();
        assertThat(f.audits).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void publishingNeedsTheProcessManagePermission(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        assertThatThrownBy(() -> svc(f).publish(f.member("mb_1", Set.of("ticket:work")), f.workspace, SampleProcesses.contractApproval(), null, "CONSOLE"))
                .extracting(e -> ((OpsKitException) e).errorKey()).isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThat(svc(f).publish(f.member("mb_1", Set.of("process:manage")), f.workspace, SampleProcesses.contractApproval(), null, "CONSOLE")
                .created()).isTrue();
    }
}
