package ai.neargo.sharehub.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构断言（B2 最后一项）。
 *
 * <h2>与 scripts/arch-guard.py 分工，不重复</h2>
 * 那四条（G1 跨模块 JOIN · G2 跨服务事务 · G3 跨服务注入 · G4 common 零业务依赖）
 * 都是**正则能判的文本特征**，放在 python 里跑得快、也能脱离构建单独跑。
 * 本类补的是正则**根本做不到**的两类：
 * <ol>
 *   <li><b>真实依赖</b> —— 字节码级，看得穿泛型、继承、嵌套类与间接调用。
 *       {@code IamMappers$RoleMapper} 这种嵌套 Mapper，逐行 grep 很难稳定认出；</li>
 *   <li><b>包循环</b> —— 循环是图的性质，逐行扫描无从发现。</li>
 * </ol>
 *
 * <h2>实测基线（2026-09-23，导入 1013 个类）</h2>
 * <pre>
 *   控制器直连 Mapper    16 处 / 1 个类（IamAdminController）→ 台账
 *   域间包循环            0  → 硬规则，不留台账
 *   依赖 service.impl     0  → 硬规则
 *   @Autowired 字段注入   0  → 硬规则
 * </pre>
 *
 * <p><b>三条零违规的规则值得立刻锁住</b>：无环、只依赖接口、构造器注入 ——
 * 这三件事**维持很便宜，失去后恢复很贵**（尤其是包循环，一旦成环就得整片重构）。
 * 现在是 0，把它钉住的成本只是这个文件。
 *
 * <h2>有意不加的一条</h2>
 * 「控制器不得依赖实体」实测 <b>139 处</b>。不在此设卡口，因为它与既有的
 * {@code known-entity-request-bodies.txt}（39 条，管入参）说的是同一件事的两半，
 * 而 DTO 化按 B3/B5 的计划随业务模块整理增量做。
 * 再开一本 139 行的台账，只会把同一笔欠账记两遍 —— <b>台账多不等于治理严</b>。
 */
class ArchitectureTest {

    /**
     * 只导入主代码。
     *
     * <p>依赖来自 {@code sharehub-app} 的 classpath，即各 svc 模块的 jar ——
     * 所以这里看到的是**整个后端**，不是 app 一个模块（实测 1013 个类）。
     */
    private static final JavaClasses ALL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            // 刻意**不**加 DO_NOT_INCLUDE_JARS：各 svc 模块是以 jar 形式进 classpath 的，
            // 排除 jar 会让导入面从 1013 塌到只剩 app 一个模块，而每条规则照样"绿"。
            .importPackages("ai.neargo.sharehub");

    /** 兜底：导入面若塌了，下面每条规则都会"零违规"地绿过去。 */
    @Test
    @DisplayName("导入面本身有效（塌了的话所有规则都会假绿）")
    void importIsNotEmpty() {
        assertThat(ALL.size())
                .as("应当导入到整个后端的主代码；数量骤降说明 classpath 或 ImportOption 变了，"
                        + "而不是「架构变干净了」")
                .isGreaterThan(300);
    }

    // ───────────────────────── 硬规则：现在是 0，就别再长出来 ─────────────────────────

    @Test
    @DisplayName("域之间不得有包循环")
    void noCyclesBetweenDomains() {
        SlicesRuleDefinition.slices()
                .matching("ai.neargo.sharehub.(*)..")
                .should().beFreeOfCycles()
                .because("循环是图的性质，正则扫不出来；而一旦成环，拆分服务时就得整片重构。"
                        + "现在是 0，维持的成本只是这条断言")
                .check(ALL);
    }

    @Test
    @DisplayName("不得跨过接口直接依赖 service.impl")
    void nobodyDependsOnServiceImpl() {
        noClasses().that().resideOutsideOfPackage("..service.impl..")
                .should().dependOnClassesThat().resideInAPackage("..service.impl..")
                .because("依赖实现类会让接口形同虚设，也让 Port 化（L4）无从下手")
                .check(ALL);
    }

    @Test
    @DisplayName("不得字段注入，一律构造器注入")
    void noFieldInjection() {
        noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("构造器注入让依赖在签名上显形，也让类可以脱离 Spring 直接 new 出来测 —— "
                        + "本仓已经全是构造器注入，这条只是别再退回去")
                .check(ALL);
    }

    // ───────────────────────── 棘轮：控制器直连 Mapper ─────────────────────────

    @Test
    @DisplayName("控制器不得绕过 service 直连 Mapper（台账只准变短）")
    void controllersDoNotTouchMappersDirectly() throws IOException {
        Set<String> offenders = offendingControllers();

        // 扫描必须有效 —— 但哨兵要绑在**被守的东西**上，而不是一个武断的数字。
        //
        // 第一版写的是「控制器数 > 20」，2026-09-23 实测假失败过一次：
        // 刚 install 完某个 svc 模块紧接着跑全量时只扫到 15 个，而代码毫无问题。
        // 一个会假失败的卡口，下一个人只会把阈值调低或加豁免 —— 比没有卡口更糟。
        //
        // 改成：台账里的 IamAdminController 必须在导入面里。它在 sharehub-svc-platform，
        // 扫不到它就说明导入没覆盖到 svc 模块，此时「零违规」毫无意义 —— 而这正是
        // 本条规则唯一会失效的方式。哨兵与被守对象绑定，就不会两边各说各话。
        assertThat(ALL.stream().anyMatch(c -> c.getSimpleName().equals("IamAdminController")))
                .as("导入面必须覆盖 svc 模块（台账里的 IamAdminController 在 sharehub-svc-platform）；"
                        + "扫不到它时的「零违规」是假的")
                .isTrue();

        Set<String> known = readLedger(ledgerPath());

        assertThat(offenders)
                .as("绕过 service 直连 Mapper 的控制器 —— 要么把查询下沉到 service，"
                        + "要么说明理由后加进 known-controller-direct-mapper.txt")
                .isSubsetOf(known);
    }

    @Test
    @DisplayName("台账里不留已整改的条目")
    void ledgerHasNoStaleEntries() throws IOException {
        Set<String> stale = new TreeSet<>(readLedger(ledgerPath()));
        stale.removeAll(offendingControllers());
        assertThat(stale)
                .as("这些控制器已经不再直连 Mapper 了，从台账删掉")
                .isEmpty();
    }

    /**
     * 直接依赖任何 {@code ..mapper..} 包中类型的控制器。
     *
     * <p>按**包**判定而不是按类名后缀：{@code IamMappers$RoleMapper} 是嵌套类，
     * 它的 simpleName 是 {@code RoleMapper}，而外层叫 {@code IamMappers} ——
     * 按后缀匹配会漏掉一整类写法。
     */
    private static Set<String> offendingControllers() {
        Set<String> out = new TreeSet<>();
        for (JavaClass c : ALL) {
            if (!isController(c)) continue;
            boolean touches = c.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> d.getTargetClass().getPackageName().contains(".mapper"));
            if (touches) out.add(c.getSimpleName());
        }
        return out;
    }

    private static boolean isController(JavaClass c) {
        return c.getSimpleName().endsWith("Controller") && !c.getSimpleName().contains("$");
    }

    private static Set<String> readLedger(Path p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static Path ledgerPath() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-controller-direct-mapper.txt"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根（含 known-controller-direct-mapper.txt）");
        return p.resolve("known-controller-direct-mapper.txt");
    }
}
