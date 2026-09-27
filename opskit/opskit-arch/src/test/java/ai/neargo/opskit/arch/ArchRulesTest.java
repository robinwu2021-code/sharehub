package ai.neargo.opskit.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 正向：产品代码满足全部规则。负对照：每条规则**只对着为它准备的那个违规样本**检查，必须报错 ——
 * 样本若一起导入，写错的规则可能被别的样本「碰巧」触发，负对照就成了假绿。
 */
class ArchRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.neargo.opskit");

    private static final Map<ArchRule, Class<?>> VIOLATION_FOR = new LinkedHashMap<>();

    static {
        VIOLATION_FOR.put(OpsKitArchRules.A1_TICKET_ONLY_USES_LOWER_API, ai.neargo.opskit.ticket.core.archfixture.TicketUsesProcessCore.class);
        VIOLATION_FOR.put(OpsKitArchRules.A1_PROCESS_ONLY_USES_LOWER_API, ai.neargo.opskit.process.core.archfixture.UsesPlatformCore.class);
        VIOLATION_FOR.put(OpsKitArchRules.A2_PLATFORM_KNOWS_NO_CAPABILITY, ai.neargo.opskit.platform.core.archfixture.PlatformUsesTicket.class);
        VIOLATION_FOR.put(OpsKitArchRules.A2_PROCESS_KNOWS_NO_TICKET, ai.neargo.opskit.process.core.archfixture.ProcessUsesTicket.class);
        VIOLATION_FOR.put(OpsKitArchRules.A3_API_ONLY_JDK, ai.neargo.opskit.process.api.archfixture.ApiUsesSpring.class);
        VIOLATION_FOR.put(OpsKitArchRules.A4_DOMAIN_IS_PURE, ai.neargo.opskit.process.core.domain.archfixture.DomainUsesSql.class);
        VIOLATION_FOR.put(OpsKitArchRules.A5_JDBC_ONLY_IN_STORE, ai.neargo.opskit.ticket.core.app.archfixture.AppUsesJdbc.class);
        VIOLATION_FOR.put(OpsKitArchRules.A6_DAO_METHODS_TAKE_TENANT_FIRST, ai.neargo.opskit.platform.store.dao.archfixture.UnscopedDao.class);
        VIOLATION_FOR.put(OpsKitArchRules.A8_OUTBOUND_HTTP_WHITELIST, ai.neargo.opskit.process.core.app.archfixture.RestCaller.class);
        VIOLATION_FOR.put(OpsKitArchRules.A10_NO_JACKSON_2, ai.neargo.opskit.ticket.core.archfixture.UsesJackson2.class);
        VIOLATION_FOR.put(OpsKitArchRules.A11_EXPLICIT_ROW_MAPPING, ai.neargo.opskit.platform.store.archfixture.ReflectiveMapping.class);
        VIOLATION_FOR.put(OpsKitArchRules.A12_USE_CASES_DECLARE_PERMISSION, ai.neargo.opskit.process.core.app.archfixture.UnguardedAppService.class);
    }

    @Test
    void everyRuleHasANegativeControl() {
        assertThat(VIOLATION_FOR.keySet()).containsExactlyInAnyOrderElementsOf(OpsKitArchRules.ALL);
    }

    @Test
    void productionCodeIsActuallyImported() {
        // 导入为空时所有正向规则都会「通过」—— 先确认真的扫到了产品类
        assertThat(PRODUCTION.containPackage("ai.neargo.opskit.commons.condition")).isTrue();
        assertThat(PRODUCTION.stream().noneMatch(c -> c.getPackageName().contains("archfixture"))).isTrue();
    }

    @TestFactory
    Stream<DynamicTest> productionCodeSatisfiesEveryRule() {
        return OpsKitArchRules.ALL.stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getDescription(), () -> rule.check(PRODUCTION)));
    }

    @TestFactory
    Stream<DynamicTest> everyRuleCatchesItsOwnViolation() {
        return VIOLATION_FOR.entrySet().stream().map(e -> DynamicTest.dynamicTest("负对照 " + e.getKey().getDescription(), () -> {
            JavaClasses sample = new ClassFileImporter().importClasses(e.getValue());
            assertThatThrownBy(() -> e.getKey().check(sample))
                    .as("规则对它的违规样本 %s 没有报错", e.getValue().getSimpleName())
                    .isInstanceOf(AssertionError.class);
        }));
    }
}
