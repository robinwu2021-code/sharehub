package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应信封的字段名契约 —— <b>复用 {@code neargo-common-core}，不各自另造</b>。
 *
 * <h2>权威</h2>
 * {@code ai.neargo.common.core.Result} 是 <b>{@code {code, message, data}}</b>，
 * {@code PageResult} 是 <b>{@code {total, list}}</b>（依赖 jar 反编译确认，
 * {@code getMessage()} 无 Jackson 改名）。{@code ApiResponseWrapper} 按它包装所有
 * Controller 返回值，所以这就是 powerbank 对外的真实形状。
 *
 * <h2>为什么必须有这条断言</h2>
 * 信封会从**三个方向**漂走，而三个方向都不会报错：
 * <ol>
 *   <li><b>安全链与限流过滤器在 Spring MVC 之外返回错误</b>，信封是
 *       {@code getWriter().write()} 手写的字符串 —— 绕过 {@code ApiResponseWrapper}，
 *       <b>没有任何类型能约束它</b>。2026-09-23 实测：{@code SecurityConfig} 与
 *       {@code AnonymousRateLimitFilter} 写 {@code "msg"}，{@code InternalTokenFilter} 写
 *       {@code "message"}，三处并存；运营端读 {@code body.message}，
 *       于是 <b>401/403/429 的后端文案永远显示不出来</b>。</li>
 *   <li><b>前端自己声明一份类型</b>。c-app 曾写 {@code PageResult{records,total}}，
 *       而 mock 也按 {@code records} 造数据 —— 两头一起错就看不出来，
 *       直到切真后端，钱包流水与订单列表全空。</li>
 *   <li><b>文档抄错</b>。{@code docs/api/README} §信封 曾写 {@code msg}，
 *       与同文件 §分页 的 {@code list} 自相矛盾，c-app 正是照抄了那一行。</li>
 * </ol>
 *
 * <p>所以守的不是"改对了"，是"别再各写一套"。**信封是跨端跨项目的契约，
 * 它的真源应当是共享依赖，不是每个项目一份。**
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class EnvelopeContractTest {

    @Test
    @DisplayName("手写错误 JSON 一律用 message，不得用 msg")
    void handWrittenEnvelopesUseMessage() throws IOException {
        List<Path> files = mainJavaFiles(backendRoot());

        // 扫描必须有效：一处手写信封都没扫到，说明正则或路径坏了，而不是「没有违规」
        long writers = files.stream().filter(f -> readsafe(f).contains("\\\"code\\\":")).count();
        assertThat(writers)
                .as("应当扫描到手写错误 JSON 的地方；一处都没有更可能是扫描坏了")
                .isGreaterThan(0);

        List<String> offenders = new ArrayList<>();
        for (Path f : files) {
            if (readsafe(f).contains("\\\"msg\\\"")) offenders.add(f.getFileName().toString());
        }

        assertThat(offenders)
                .as("手写错误 JSON 的字段名必须是 message（契约 Result{code,message,data}）—— "
                        + "写 msg 的话前端读不到，且不会有任何报错")
                .isEmpty();
    }

    @Test
    @DisplayName("两个前端的信封与分页类型都对齐共享契约")
    void frontendTypesMatchTheSharedContract() throws IOException {
        Path repo = backendRoot().getParent();
        List<String> offenders = new ArrayList<>();

        for (String app : List.of("ops-web", "c-app")) {
            Path types = repo.resolve(app).resolve("src/types/index.ts");
            if (!Files.isRegularFile(types)) types = repo.resolve(app).resolve("lib/types/common.ts");
            if (!Files.isRegularFile(types)) continue;
            String src = Files.readString(types, StandardCharsets.UTF_8);

            check(offenders, app, src, "interface Result", "message", "msg");
            check(offenders, app, src, "interface PageResult", "list", "records");
        }

        // 至少要检到一个应用，否则路径变了而断言空转
        assertThat(offenders).as("信封/分页类型必须与 neargo-common-core 一致："
                + "Result{code,message,data} · PageResult{total,list}").isEmpty();
    }

    /** 在 {@code decl} 声明块里要求出现 {@code want}、不得出现 {@code avoid}。 */
    private static void check(List<String> out, String app, String src, String decl, String want, String avoid) {
        int i = src.indexOf(decl);
        if (i < 0) return;
        String block = src.substring(i, Math.min(src.length(), i + 220));
        if (!block.contains(want)) out.add(app + " 的 " + decl + " 缺字段 " + want);
        if (block.contains(avoid + ":")) out.add(app + " 的 " + decl + " 仍用 " + avoid);
    }

    private static String readsafe(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-arch-exemptions.txt"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根");
        return p;
    }

    private static List<Path> mainJavaFiles(Path backend) throws IOException {
        try (Stream<Path> s = Files.walk(backend)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .toList();
        }
    }
}
