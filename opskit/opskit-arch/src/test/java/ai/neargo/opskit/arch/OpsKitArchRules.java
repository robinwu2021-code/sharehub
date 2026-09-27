package ai.neargo.opskit.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.Permission;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.List;

/** 21/05 §六 的 A1–A12（A7、A9 是源码扫描，见 SourceScanRules）。 */
final class OpsKitArchRules {

    private OpsKitArchRules() {
    }

    /** A1：上层只经下层的 api / client，不碰下层的 core / store。 */
    static final ArchRule A1_TICKET_ONLY_USES_LOWER_API = noClasses()
            .that().resideInAPackage("ai.neargo.opskit.ticket..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ai.neargo.opskit.process.core..", "ai.neargo.opskit.process.store..",
                    "ai.neargo.opskit.platform.core..", "ai.neargo.opskit.platform.store..")
            .as("A1 工单只经流程 / 平台的 api 与 client");

    static final ArchRule A1_PROCESS_ONLY_USES_LOWER_API = noClasses()
            .that().resideInAPackage("ai.neargo.opskit.process..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ai.neargo.opskit.platform.core..", "ai.neargo.opskit.platform.store..")
            .as("A1 流程只经平台的 api 与 client");

    /** A2：下层对上层无知。 */
    static final ArchRule A2_PLATFORM_KNOWS_NO_CAPABILITY = noClasses()
            .that().resideInAPackage("ai.neargo.opskit.platform..")
            .should().dependOnClassesThat().resideInAnyPackage("ai.neargo.opskit.process..", "ai.neargo.opskit.ticket..")
            .as("A2 平台不知道流程与工单");

    static final ArchRule A2_PROCESS_KNOWS_NO_TICKET = noClasses()
            .that().resideInAPackage("ai.neargo.opskit.process..")
            .should().dependOnClassesThat().resideInAPackage("ai.neargo.opskit.ticket..")
            .as("A2 流程不知道工单");

    /** A3：共享库与各 api 只依赖 JDK（及下层的 api / commons，方向由 A2 另管）。 */
    static final ArchRule A3_API_ONLY_JDK = classes()
            .that().resideInAnyPackage("ai.neargo.opskit.commons..", "ai.neargo.opskit.*.api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax.sql..", "javax.crypto..",
                    "ai.neargo.opskit.commons..", "ai.neargo.opskit.platform.api..",
                    "ai.neargo.opskit.process.api..", "ai.neargo.opskit.ticket.api..")
            .as("A3 commons 与 *-api 只依赖 JDK");

    /** A4：领域层可纯单元测试。 */
    static final ArchRule A4_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("ai.neargo.opskit..core.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "ai.neargo.opskit..store..")
            .as("A4 领域层不依赖 Spring、JDBC 与 store");

    /** A5：SQL 只在 store。 */
    static final ArchRule A5_JDBC_ONLY_IN_STORE = noClasses()
            .that().resideOutsideOfPackage("ai.neargo.opskit..store..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .as("A5 只有 *-store 使用 Spring JDBC");

    /** A6：DAO 的每个公开方法第一个参数是租户键 —— 漏传空间在编译期就暴露。 */
    static final ArchRule A6_DAO_METHODS_TAKE_TENANT_FIRST = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().resideInAPackage("ai.neargo.opskit..store.dao..")
            // 只约束 DAO 本身；DAO 里嵌套的结果记录（如 AuditDao.Entry）的访问器不是查询
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Dao")
            .should(new ArchCondition<JavaMethod>("take WorkspaceId or AccountId as the first parameter") {
                @Override
                public void check(JavaMethod m, ConditionEvents events) {
                    List<JavaClass> params = m.getRawParameterTypes();
                    boolean ok = !params.isEmpty()
                            && (params.get(0).isEquivalentTo(WorkspaceId.class) || params.get(0).isEquivalentTo(AccountId.class));
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(m, m.getFullName() + " 的第一个参数不是 WorkspaceId / AccountId"));
                    }
                }
            })
            .as("A6 DAO 公开方法第一个参数是租户键");

    /** A8：出站 HTTP 只在事件投递、令牌校验与 HTTP 客户端里（R1 及其唯一例外）。 */
    static final ArchRule A8_OUTBOUND_HTTP_WHITELIST = noClasses()
            .that().resideOutsideOfPackages(
                    "ai.neargo.opskit.platform.core.outbox..", "ai.neargo.opskit.platform.core.auth..",
                    "ai.neargo.opskit..client.http..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.client..", "org.springframework.web.service..", "java.net.http..")
            .as("A8 出站 HTTP 只在 outbox / auth / *-client.http");

    /** A10：不用 Jackson 2。 */
    static final ArchRule A10_NO_JACKSON_2 = noClasses()
            .that().resideInAPackage("ai.neargo.opskit..")
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
            .as("A10 不引用 Jackson 2");

    /** A11：显式行映射，列改名在编译期暴露。 */
    static final ArchRule A11_EXPLICIT_ROW_MAPPING = noClasses()
            .that().resideInAPackage("ai.neargo.opskit..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.BeanPropertyRowMapper")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.DataClassRowMapper")
            .as("A11 不用反射式行映射");

    /** A12：应用服务的每个公开用例声明所需产品权限，或写明为何是系统动作；且权限码必须在目录里。 */
    static final ArchRule A12_USE_CASES_DECLARE_PERMISSION = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().resideInAPackage("ai.neargo.opskit..core.app..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("AppService")
            .should(new ArchCondition<JavaMethod>("declare @RequiresPermission with known codes or @SystemAction") {
                @Override
                public void check(JavaMethod m, ConditionEvents events) {
                    boolean system = m.isAnnotatedWith(SystemAction.class) || m.getOwner().isAnnotatedWith(SystemAction.class);
                    if (m.isAnnotatedWith(RequiresPermission.class)) {
                        String[] codes = m.getAnnotationOfType(RequiresPermission.class).value();
                        Arrays.stream(codes).filter(c -> Permission.ofCode(c).isEmpty()).forEach(c ->
                                events.add(SimpleConditionEvent.violated(m, m.getFullName() + " 用了目录里没有的权限码 " + c)));
                    } else if (!system) {
                        events.add(SimpleConditionEvent.violated(m, m.getFullName() + " 既没有 @RequiresPermission 也没有 @SystemAction"));
                    }
                }
            })
            .as("A12 用例声明产品权限");

    static final List<ArchRule> ALL = List.of(
            A1_TICKET_ONLY_USES_LOWER_API, A1_PROCESS_ONLY_USES_LOWER_API,
            A2_PLATFORM_KNOWS_NO_CAPABILITY, A2_PROCESS_KNOWS_NO_TICKET,
            A3_API_ONLY_JDK, A4_DOMAIN_IS_PURE, A5_JDBC_ONLY_IN_STORE, A6_DAO_METHODS_TAKE_TENANT_FIRST,
            A8_OUTBOUND_HTTP_WHITELIST, A10_NO_JACKSON_2, A11_EXPLICIT_ROW_MAPPING, A12_USE_CASES_DECLARE_PERMISSION);
}
