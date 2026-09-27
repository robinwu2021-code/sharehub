package ai.neargo.opskit.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 字节码里看不到字符串常量与 SQL 文件，这两条规则扫源码：
 * A7 每个模块的持久化只碰自己的表前缀（禁止跨前缀 JOIN）；A9 产品代码里不出现任何客户名。
 */
final class SourceScanRules {

    /** store 模块目录名 → 允许的表前缀。 */
    static final Map<String, String> STORE_PREFIX = Map.of(
            "platform-store", "plat_", "process-store", "proc_", "ticket-store", "tkt_");

    private static final Pattern TABLE = Pattern.compile("\\b(plat|proc|tkt)_[a-z0-9_]+");
    static final Pattern CUSTOMER = Pattern.compile(
            "(?i)\\b(sharehub|powerbank|ichain|tradeagent|ai-shop|aishop|shop)\\b");

    private SourceScanRules() {
    }

    /** 以 opskit 根目录为起点，列出所有 src/main 下的 .java 与 .sql。 */
    static List<Path> mainSources(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> tablePrefixViolations(Path root) {
        List<String> out = new ArrayList<>();
        for (Path file : mainSources(root)) {
            String store = STORE_PREFIX.keySet().stream().filter(m -> file.toString().contains("/" + m + "/")).findFirst().orElse(null);
            Matcher m = TABLE.matcher(read(file));
            while (m.find()) {
                String prefix = m.group(1) + "_";
                if (store == null) {
                    out.add(root.relativize(file) + " 在 store 之外引用了表 " + m.group());
                } else if (!prefix.equals(STORE_PREFIX.get(store))) {
                    out.add(root.relativize(file) + " 引用了别的模块的表 " + m.group());
                }
            }
        }
        return out;
    }

    static List<String> customerNameViolations(Path root) {
        List<String> out = new ArrayList<>();
        for (Path file : mainSources(root)) {
            Matcher m = CUSTOMER.matcher(read(file));
            while (m.find()) {
                out.add(root.relativize(file) + " 出现客户名 " + m.group());
            }
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
