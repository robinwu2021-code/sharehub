package ai.neargo.opskit.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceScanRulesTest {

    /** surefire 的工作目录是本模块目录，其上一级就是 opskit 根。 */
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @Test
    void scansTheRealTree() {
        assertThat(SourceScanRules.mainSources(ROOT)).as("扫描范围为空说明路径算错了，规则会假绿")
                .anyMatch(p -> p.toString().endsWith("V1__scheduled_tasks.sql"));
    }

    @Test
    void a7EveryStoreOnlyTouchesItsOwnTables() {
        assertThat(SourceScanRules.tablePrefixViolations(ROOT)).isEmpty();
    }

    @Test
    void a9ProductCodeNamesNoCustomer() {
        assertThat(SourceScanRules.customerNameViolations(ROOT)).isEmpty();
    }

    @Test
    void a7CatchesCrossPrefixAndTablesOutsideStore(@TempDir Path tmp) throws IOException {
        write(tmp, "process/process-store/src/main/resources/db/process/mysql/V1__x.sql", "CREATE TABLE plat_leak (id INT);");
        write(tmp, "ticket/ticket-core/src/main/java/X.java", "class X { String sql = \"SELECT * FROM tkt_ticket\"; }");
        assertThat(SourceScanRules.tablePrefixViolations(tmp)).hasSize(2);
    }

    @Test
    void a9CatchesCustomerNames(@TempDir Path tmp) throws IOException {
        write(tmp, "ticket/ticket-core/src/main/java/Y.java", "class Y { String s = \"ShareHub special case\"; }");
        write(tmp, "ticket/ticket-core/src/main/java/Z.java", "class Z { String s = \"workshop is fine\"; }");
        assertThat(SourceScanRules.customerNameViolations(tmp)).hasSize(1);
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
