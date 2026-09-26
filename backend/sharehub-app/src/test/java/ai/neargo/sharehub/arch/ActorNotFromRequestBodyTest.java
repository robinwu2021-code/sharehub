package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **「谁做的」不能由请求体说**。审批人 / 验收人 / 复核人一律由服务端按会话回填。
 *
 * <h2>为什么这类缺陷看不出来</h2>
 * {@code audited_by} / {@code reviewed_by} 这类列是**合规字段** —— 出事时用来回答
 * 「谁放行的」。它能由调用方指定的话，整条审批链的证据就是自述的：
 * 谁都可以声称是别人验收的，而**四眼原则正是靠这一列成立的**。
 *
 * <p>界面上完全正常：按钮照点、单子照关、列表里也真的显示了一个人名 ——
 * 只是那个人名是请求里写的，不是操作的人。没有报错，没有告警，
 * 只在有人去查「这单谁放行的」时才会发现答案不可信，而那通常已经太晚。
 *
 * <h2>这条约定早就有，只是没有落脚点</h2>
 * {@code StlWithdrawal#auditorNo} 的实体注释上写死了：
 * 「审批人留痕，<b>服务端按会话回填</b>，不信前端传值」。
 * 而 2026-09-27 实测发现 {@code WoExtDtos.CloseReq} 有一个 {@code auditorNo}，
 * 服务端还写着「入参显式指定优先」—— 同一个仓库里两条相反的做法，
 * 差别不是设计，是没人把那句注释变成卡口。
 *
 * <p>（前端从来没传过那个字段，所以它不是在用的功能，只是给伪造留的门。）
 *
 * <h2>为什么只认这几个词</h2>
 * 模式里**刻意不含** {@code assigneeNo} / {@code handlerNo} / {@code employeeNo}
 * / {@code targetNo} —— 那些是**被操作的对象**（「指派谁去接管」），
 * 由调用方指定完全正确。会误伤的卡口会被人加豁免绕过去，那就白立了。
 *
 * <p>只认语义是「执行这个动作的那个人」的命名。新增一个同类命名而它确实是对象时，
 * 把它改名（叫 {@code targetXxx} / {@code assigneeXxx}）比加豁免更好 ——
 * 因为读代码的人也会误解。
 */
class ActorNotFromRequestBodyTest {

    /** 语义是「执行动作的人」的字段名。对象类命名（assignee/handler/target/employee）刻意不在内。 */
    private static final Pattern ACTOR = Pattern.compile(
            "\\b(auditor|approver|reviewer|closer|submitter|actor"
                    + "|auditedBy|reviewedBy|approvedBy|closedBy|submittedBy|createdBy|updatedBy)"
                    + "(No|By|Name)?\\b");

    /** 请求体 record：`public record XxxReq(...)`，跨行。 */
    private static final Pattern REQ_RECORD = Pattern.compile(
            "public record (\\w*Req)\\s*\\((.*?)\\)\\s*\\{", Pattern.DOTALL);

    @Test
    @DisplayName("★ 请求体里不许出现审批人/验收人身份字段 —— 那一列是合规证据，只能由会话决定")
    void request_bodies_do_not_carry_the_actor() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaSources()) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            if (!src.contains("public record")) continue;
            Matcher rec = REQ_RECORD.matcher(src);
            while (rec.find()) {
                Matcher a = ACTOR.matcher(rec.group(2));
                while (a.find()) {
                    offenders.add(p.getFileName() + " · " + rec.group(1) + " · " + a.group(0));
                }
            }
        }
        assertThat(offenders)
                .as("""
                        这些请求体让调用方自己说「谁做的」。那一列是合规证据 —— 改成由服务端
                        按会话回填（SecurityUtils.currentUser()），并把字段从请求体里删掉：
                        留着等于「前端还传、后端静默忽略」，那是另一种谎。
                        若它其实是**被操作的对象**，请改名为 targetXxx / assigneeXxx ——
                        读代码的人也会照字面误解。
                        %s""", String.join("\n  ", offenders))
                .isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        // 从 sharehub-app 往上一级找兄弟模块 —— 与本包其它卡口一致的定位方式
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sharehub-common"))) root = root.getParent();
        assertThat(root).as("定位不到 backend 根目录，本卡口会静默放过一切").isNotNull();
        try (var s = Files.walk(root)) {
            return s.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .toList();
        }
    }
}
