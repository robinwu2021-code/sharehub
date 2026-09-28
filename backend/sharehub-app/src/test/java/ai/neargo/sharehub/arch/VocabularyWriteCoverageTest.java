package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表列的写入侧**待核实**台账 —— 只准变短，且新列必须当场表态。
 *
 * <h2>为什么需要这一条（事后卡口答不了的那个问题）</h2>
 * {@code StoredValueInVocabularyTest} 比对**库里已存的值**与列注释，是兜底。
 * 但脏数据必须**先落库**才会被它发现 —— 而那时该查的是当初写它的那次请求，现场早没了。
 * 2026-09-27 一天之内串错三次（V82 告警等级 / V84 对账差异 / V85 活动类型与拉黑原因），
 * 三次的写入方都绕开了校验。
 *
 * <p>最值钱的一条实证：{@code share_rule.mode} / {@code share_record.mode} 曾有 17 行
 * {@code RATE}（V66 归一），写它们的是 {@code ShareServiceImpl.saveRule} ——
 * 而 {@code ShareMode.of()} <b>那时已经存在、只是这里没调</b>。
 * 词表有定义、校验有实现、中间那一步没接上，<b>三者都不报错</b>。
 *
 * <h2>这一条守的不是「全都接上」，是「没有一列是悄悄多出来的」</h2>
 * 288 列的入口不可能一次接完，所以本条**不要求全覆盖**，要求两件事：
 * <ol>
 *   <li><b>新增词表列必须当场表态</b> —— 要么接上校验，要么进台账并写明为什么先不接。
 *       这正是那三次串错的共同形状：列加上了，谁都没想起写入侧；</li>
 *   <li><b>未覆盖数只准降</b> —— 接一个删一行，不许反向增长。</li>
 * </ol>
 *
 * <h2>为什么不自动判「这一列接没接」</h2>
 * 想过从调用链推（实体 setter ← 请求体 ← 有没有过 {@code of()}），但那要跨
 * 控制器 / service / mapper 三层做数据流分析，而本仓的写入路径有三种形态
 * （新旧两个对象、就地 set、部分更新）。判不准的卡口比没有更糟 ——
 * 它会让人以为「绿了就是接上了」。
 *
 * <p>所以覆盖与否由**人写进台账**，真正的证明在 {@code VocabularyRejectedOnWriteTest}
 * （从端点验：POST 词表外的值必须 400，每格带正对照）。本条只管账对不对得上。
 */
@SpringBootTest
@DisplayName("词表列写入侧覆盖台账")
class VocabularyWriteCoverageTest {

    private static final String LEDGER = "known-unvalidated-vocab-columns.txt";

    /**
     * **已核实写入侧有校验**的列。两种来源，都算：
     * <ul>
     *   <li>在 {@code VocabularyRejectedOnWriteTest} 里逐格验过（最好 —— 以后改坏会红）；</li>
     *   <li>读代码确认过写入口调了枚举的 {@code of()}（如 {@code AlarmCodeServiceImpl}
     *       那 8 列）。</li>
     * </ul>
     *
     * <p><b>建账时我把这两件事混了</b>：先只按第一种推，于是 {@code dev_alarm_code}
     * 另外 7 列（domain / subjectType / evalType / disposition / mergeScope /
     * recoverRule / basePriority）被误记成「没接」—— 而它们在
     * {@code AlarmCodeServiceImpl#beforeSave} 里挨个 of 过。
     * 台账的数字一旦虚高就没人信，而那是它唯一的价值。
     *
     * <p>写在这里而不是从那个测试类反射提取：用例名是给人读的句子，
     * 解析它等于把断言的可读性和卡口的正确性绑在一起 —— 改个标题就可能让统计变样。
     */
    private static final Set<String> COVERED = new TreeSet<>(Set.of(
            "share_rule.dimension", "share_rule.mode", "share_rule.basis",
            "share_record.mode", "mkt_campaign.kind", "pay_channel.mode",
            "agt_commission.mode", "notify_blacklist.reason", "notify_blacklist.channel",
            "notify_template.channel", "usr_free_whitelist.reason", "usr_free_whitelist.quota_type",
            "md_problem.category", "md_problem.suggested_action",
            // dev_alarm_code：8 列都在 AlarmCodeServiceImpl#beforeSave 里逐个 of 过，
            // 其中只有 level 另有端点级用例（V82 白屏那一列）
            "dev_alarm_code.level", "dev_alarm_code.domain", "dev_alarm_code.subject_type",
            "dev_alarm_code.eval_type", "dev_alarm_code.disposition", "dev_alarm_code.merge_scope",
            "dev_alarm_code.recover_rule", "dev_alarm_code.base_priority",
            // 2026-09-28 接上：req.status() 原先原样落库（InvoiceServiceImpl），
            // 现在走 InvoiceStatus.of() 并归一大小写，VocabularyRejectedOnWriteTest 两格
            "fin_invoice.status",
            // ——— 以下是第三种来源：**前端根本传不了这一列**（见 SERVER_SET 说明）———
            "stl_payout_account.payee_type", "stl_adjustment.payee_type",
            "share_record.payee_type", "stl_settlement.payee_type",

            // ——— 2026-09-28 第二批：从 known-entity-writable-fields 的交集查起 ———
            // 那份台账记的是「实体当请求体」上客户端可写的字段。与本清单取交集，
            // 得到 18 个**前端直接写得进去、且没有任何 setXxx 拦在中间**的词表列 ——
            // 风险最高的一批，而且是自动算出来的，不靠谁记得。
            //
            // 新接上校验（各带端点格）：
            "iam_dept.status", "iam_employee.status",
            "dev_code_batch.status", "dev_code_batch.code_type",
            "loc_lead.share_mode", "md_problem.status", "usr_invoice_title.type",
            // 读代码核实：原来就有校验，只是建账时没人核过（台账虚高的那部分）
            "ad_slot.status",                                    // AdSlotStatus.of
            "notify_template.status",                            // NotifyTemplateStatus.of
            "dev_alarm_rule.channel", "dev_alarm_rule.method", "dev_alarm_rule.status",
            "wo_sla_rule.wo_type",                               // WorkOrderType.of
            "loc_lead.stage", "loc_lead.owner_type",             // checkStage / checkOwnerType
            // 服务端全权（第三种答案）：创建写常量、编辑抄 current、状态迁移走专门端点
            "notify_blacklist.status", "usr_free_whitelist.status"));

    /*
     * 核实一列时会遇到第三种答案，它和「接上了校验」一样牢靠，有时更牢靠：
     * **这一列压根不来自请求体**。上面那四个 payee_type 就是：
     *
     *   · stl_payout_account.payee_type —— PayoutAccountServiceImpl 有白名单
     *     PAYEE_TYPES（AGENT/VENUE），不在其中直接 400。这是真的校验；
     *   · stl_adjustment.payee_type —— AdjustmentServiceImpl 两处都写死常量 VENUE；
     *   · share_record.payee_type —— ShareGeneratorImpl 抄自 share_rule.dimension
     *     （那一列已在本清单里），ReferFeeGeneratorImpl 写死 AGENT；
     *   · stl_settlement.payee_type —— SettlementServiceImpl 从上面两张表的表头抄。
     *
     * 判据是 grep 全部 setPayeeType 的写入点（8 处，全在 svc-finance），逐个看来源。
     * 「派生自另一个已覆盖的列」要连着上游一起看：dimension 哪天松了，这四列跟着松。
     *
     * ⚠️ 顺带查出来两件不在本卡口范围内的事，记在这儿免得下次重查：
     *   · stl_withdrawal.payee_type **既没注释也没校验**，WithdrawalServiceImpl
     *     直接 e.setPayeeType(req.payeeType())。没注释 = 本卡口看不见它；
     *   · fin_invoice.payee_type 的注释是占位文字「既有DDL同名列×3」，同样看不见。
     * 两个都该补注释（那是 DdlStatusVocabularyCommentTest 的地盘），补完会自动进本清单。
     */

    @Autowired
    JdbcTemplate jdbc;

    private Set<String> cache;

    @Test
    @DisplayName("★★ 每个词表列要么已接校验、要么在台账里 —— 不许悄悄多出一列没人管的")
    void every_vocabulary_column_is_either_covered_or_on_the_ledger() throws IOException {
        Set<String> all = vocabularyColumns();
        assertThat(all).as("前提：读得到词表列（读不到会让本条恒绿）").hasSizeGreaterThan(200);

        Set<String> ledger = readLedger();
        Set<String> unaccounted = new TreeSet<>(all);
        unaccounted.removeAll(COVERED);
        unaccounted.removeAll(ledger);

        assertThat(unaccounted)
                .as("""
                        这些词表列**既没接写入侧校验、也不在台账里** —— 多半是新加的列忘了表态。
                        二选一：
                          · 写入口调枚举的 ofNullable()（传了才校验），并往
                            VocabularyRejectedOnWriteTest 加一格（带正对照），再把它加进 COVERED；
                          · 或者把它加进 %s，并写明为什么先不接。
                        别指望事后那条 StoredValueInVocabularyTest 替你兜着 ——
                        它要等脏数据先落库，而那时现场已经没了。
                        """.formatted(LEDGER))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 台账里不许有已经接上的列 —— 接了就要删行，否则数字永远降不下来")
    void the_ledger_does_not_list_already_covered_columns() throws IOException {
        Set<String> stale = new TreeSet<>(readLedger());
        stale.retainAll(COVERED);
        assertThat(stale)
                .as("这些列已经接上校验了，请从 %s 删掉对应行 —— 留着会让「未覆盖数」虚高，"
                        + "而这个数字是唯一能说明进度的东西", LEDGER)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 台账里不许有已经不存在的列 —— 删表改列之后要跟着清")
    void the_ledger_does_not_list_columns_that_no_longer_exist() throws IOException {
        Set<String> all = vocabularyColumns();
        Set<String> ghosts = new TreeSet<>(readLedger());
        ghosts.removeAll(all);
        assertThat(ghosts)
                .as("""
                        这些行在库里已经找不到对应的词表列了（列删了 / 改名了 / 注释改得不再声明词表）。
                        留着它们会让未覆盖数虚高 —— 而那个数字一旦不可信，
                        这份台账就退化成了一张没人看的清单。
                        """)
                .isEmpty();
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 写入侧要管的词表列。判据与 {@code StoredValueInVocabularyTest} 共用
     * （{@link VocabularyColumns}），<b>唯一的分歧是空列</b>：
     * 库里还没有取值时形状判不出来，事后卡口跳过它，本条保留它 ——
     * 新建的空表恰恰是最该当场表态的。理由写在 {@code VocabularyColumns} 类注释里。
     *
     * <p>一次扫描要对每一列各查一次取值分布，三条用例共用一份结果。
     */
    private Set<String> vocabularyColumns() {
        if (cache == null) {
            Set<String> out = new TreeSet<>();
            for (VocabularyColumns.Column c : VocabularyColumns.scan(jdbc)) {
                if (c.looksLikeEnum() || c.hasNoValues() || c.classicName()) out.add(c.key());
            }
            cache = out;
        }
        return cache;
    }

    private Set<String> readLedger() throws IOException {
        Path p = backendRoot().resolve(LEDGER);
        Set<String> out = new TreeSet<>();
        List<String> bad = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")) {
                bad.add(line);
                continue;
            }
            out.add(t);
        }
        assertThat(bad).as("台账行格式应为 `表.列`（注释用 # 开头）").isEmpty();
        return out;
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve(LEDGER))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根（含 " + LEDGER + "）");
        return p;
    }
}
