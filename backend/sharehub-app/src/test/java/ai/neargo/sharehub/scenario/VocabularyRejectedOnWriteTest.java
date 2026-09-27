package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表外的值**写不进去** —— 这一条与 {@code StoredValueInVocabularyTest} 是一对。
 *
 * <h2>为什么事后卡口不够</h2>
 * {@code StoredValueInVocabularyTest} 比的是**库里已经存着的值**与列注释：
 * 它很全（149 列），但脏数据必须先落库才会被它发现，而那时该查的是当初写它的那次请求 ——
 * 现场早没了。实测过的代价：{@code share_rule.mode} / {@code share_record.mode} 有 17 行
 * {@code RATE}（V66 归一），写它们的那条路是 {@code ShareServiceImpl.saveRule}，
 * 而 {@code ShareMode.of()} 那时**已经存在、只是没人调** ——
 * 词表有定义、校验有实现、中间那一步没接上，三者都不报错。
 *
 * <p>所以本条从**入口**验：POST 一个词表外的值，必须 400 被拒，而不是 200 落库。
 *
 * <h2>每格都有正对照，否则「全都 400」也能让它绿</h2>
 * 只断言「非法值被拒」的用例，在一个把所有请求都拒掉的实现上同样全绿 ——
 * 那种实现的症状是功能全废，而这条用例会说一切正常。
 * 所以每格都先发一个**合法值**并断言它成功。
 *
 * <h2>⚠️ {@code @Transactional} 在这里不回滚，必须显式清理</h2>
 * 本类打的是**真 HTTP 端点**，写入发生在服务端自己的事务里 ——
 * 测试方法上那个 {@code @Transactional} 盖不住它。第一版加了 {@code @Transactional}
 * 就以为干净了，实测在共享测试库里留下 <b>10 条分润规则 + 4 个活动 + 4 行拉黑</b>，
 * 而负对照那一轮更是把 {@code share_rule.mode = 'RATE'} 真写了进去 ——
 * <b>验「脏值写不进去」的用例，自己把脏值写了进去</b>。
 * 当场是姊妹卡口 {@code StoredValueInVocabularyTest} 红了才发现的（它确实管用）。
 *
 * <p>{@code test_sharehub} 是累积型共享资产（见 CLAUDE.md），
 * 所以本类用 {@link #cleanUp} 按标记行显式删。<b>加新格时必须一起加清理</b>，
 * 否则每天跑每天攒，下一个查「这些规则哪来的」的人会查很久。
 *
 * <h2>不是所有词表列都在这里</h2>
 * 本条覆盖的是 2026-09-27 这一批接上校验的入口。词表列共 149 个（那是事后卡口的覆盖数），
 * 逐个接校验是渐进的事 —— 新增入口时**请在这里加一格**，
 * 而不是指望事后那条替你兜着。
 */
class VocabularyRejectedOnWriteTest extends ApiTestSupport {

    /** 非法但形状像枚举的值。用 RATE 是刻意的：它就是当年被写进 mode 的那个。 */
    private static final String BAD = "RATE";

    /** 本类造的行一律带这个标记，{@link #cleanUp} 按它删 —— 见类注释。 */
    private static final String MARK = "词表卡口测试数据";

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * ⚠️ 这里任何一句 SQL 写错，**全部格子都会变成 Errors**（不是 Failures）——
     * {@code @AfterEach} 抛异常算这个方法失败。实测过一次：给 {@code dev_alarm_code}
     * 写了不存在的 {@code name} 列，7 格里 6 格瞬间报 Errors，而它们本身好着。
     * 排障时先看是 Failures 还是 Errors：Errors 往往不在被测代码里。
     */
    @AfterEach
    void cleanUp() {
        /*
         * 硬删而不是软删：这些是测试垃圾，不是业务数据，留着软删行还会继续占编号
         * （见 BizNoSequence 的「归档一行就在编号上挖洞」）。
         */
        jdbc.update("DELETE FROM share_rule WHERE payee_name = ?", MARK);
        jdbc.update("DELETE FROM mkt_campaign WHERE name = ?", MARK);
        jdbc.update("DELETE FROM notify_blacklist WHERE target LIKE '050000%'");
        jdbc.update("DELETE FROM mkt_push WHERE title = ?", MARK);
        jdbc.update("DELETE FROM coupon_tpl WHERE name = ?", MARK);
        // dev_alarm_code 没有 name 列，业务键是 code —— 按探针码删
        jdbc.update("DELETE FROM dev_alarm_code WHERE code LIKE 'TEST_VOCAB_PROBE%'");
    }

    @Test
    @DisplayName("★★ share_rule 的 dimension / mode 非法值一律 400——这条路当年写进过 17 行 RATE")
    void share_rule_rejects_out_of_vocabulary() {
        String t = login("FINANCE");

        // 正对照：合法值必须能存进去。没有这一格，「全都拒」也是绿的
        var ok = post("/api/trade/share-rules", Map.of(
                "dimension", "AGENT", "payeeNo", "AG0001", "payeeName", MARK,
                "basis", "OPERATE", "mode", "LEDGER", "rate", "0.05", "currency", "AED"), t);
        assertThat(ok.status).as("合法值应当能存：%s", ok.msg()).isEqualTo(200);

        // mode 非法
        var badMode = post("/api/trade/share-rules", Map.of(
                "dimension", "AGENT", "payeeNo", "AG0001", "payeeName", MARK,
                "basis", "OPERATE", "mode", BAD, "rate", "0.05", "currency", "AED"), t);
        assertThat(badMode.status).as("mode=%s 竟然被接受了：%s", BAD, badMode.msg()).isEqualTo(400);

        // dimension 非法
        var badDim = post("/api/trade/share-rules", Map.of(
                "dimension", BAD, "payeeNo", "AG0001", "payeeName", MARK,
                "basis", "OPERATE", "mode", "LEDGER", "rate", "0.05", "currency", "AED"), t);
        assertThat(badDim.status).as("dimension=%s 竟然被接受了：%s", BAD, badDim.msg()).isEqualTo(400);

        // basis 非法
        var badBasis = post("/api/trade/share-rules", Map.of(
                "dimension", "AGENT", "payeeNo", "AG0001", "payeeName", MARK,
                "basis", BAD, "mode", "LEDGER", "rate", "0.05", "currency", "AED"), t);
        assertThat(badBasis.status).as("basis=%s 竟然被接受了：%s", BAD, badBasis.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ basis 留空是合法的——实测 1108 行 AGENT 规则里 188 行为空，那是「通用规则」")
    void blank_basis_is_legal_not_missing() {
        /*
         * 这一格挡的是「把校验写紧了」：按 DDL 注释那句「VENUE 维度留空串」推，
         * 很容易得出「AGENT 维度 basis 必填」，于是 188 行对的写入被一起拒掉。
         * 取价时找不到精确 basis 会退回通用规则（ShareGeneratorImpl.ruleOf），
         * 空 basis 正是那条退路。
         */
        String t = login("FINANCE");
        var r = post("/api/trade/share-rules", Map.of(
                "dimension", "AGENT", "payeeNo", "AG0001", "payeeName", MARK,
                "basis", "", "mode", "LEDGER", "rate", "0.05", "currency", "AED"), t);
        assertThat(r.status).as("空 basis 被拒了，那 188 行通用规则就再也存不进来：%s", r.msg()).isEqualTo(200);
    }

    @Test
    @DisplayName("★ mkt_campaign.kind 非法值 400——此前原样 setKind 落库")
    void campaign_kind_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/user/campaigns", Map.of(
                "name", MARK, "kind", "COUPON_PUSH", "status", "DRAFT"), t);
        assertThat(ok.status).as("合法 kind 应当能存：%s", ok.msg()).isEqualTo(200);

        var bad = post("/api/user/campaigns", Map.of(
                "name", MARK, "kind", "WHATEVER", "status", "DRAFT"), t);
        assertThat(bad.status).as("kind=WHATEVER 竟然被接受了：%s", bad.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★★ dev_alarm_code.level 非法值 400——V82 白屏那一列，7 个兄弟都有闸就它没有")
    void alarm_code_level_rejects_work_order_priority() {
        /*
         * V82：dev_alarm / dev_alarm_code 的 level 存进了**工单优先级**的词表
         * （LOW/MEDIUM/HIGH/URGENT），而这一列声明的是 INFO/WARN/CRITICAL。
         * 运营端 /alarms 整页白屏、等级筛选选哪一档都筛不出东西。
         * 当时修了数据（归一迁移）与读侧（StatusBadge 降级），**入口一直没补** ——
         * 同样的写入再来一次，同样的白屏会再来一次。这一格钉住它。
         *
         * 用 HIGH 而不是随便一个乱码：它是**真实串错过的那个值**，
         * 而且 basePriority 与 level 在同一个表单上相邻、四档 vs 三档一个词都不重合。
         */
        String t = login("ADMIN");

        var ok = post("/api/ops/alarms/codes", Map.of(
                "code", "TEST_VOCAB_PROBE", "message", MARK, "level", "WARN",
                "domain", "SERVICE", "subjectType", "CABINET", "evalType", "STATE"), t);
        assertThat(ok.status).as("合法 level 应当能存：%s", ok.msg()).isEqualTo(200);

        var bad = post("/api/ops/alarms/codes", Map.of(
                "code", "TEST_VOCAB_PROBE2", "message", MARK, "level", "HIGH",
                "domain", "SERVICE", "subjectType", "CABINET", "evalType", "STATE"), t);
        assertThat(bad.status)
                .as("level=HIGH（工单优先级的词表）竟然被接受了——V82 白屏会再来一次：%s", bad.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★ mkt_push 的 channel / audience_type 非法值 400——SMS 那档已随前端下拉一起撤掉")
    void push_channel_and_audience_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/user/push-messages", Map.of(
                "title", MARK, "content", "正对照", "channel", "APP_PUSH",
                "audienceType", "ALL"), t);
        assertThat(ok.status).as("合法渠道应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * SMS 是刻意选的：运营端的渠道下拉曾经真有这一档（label 表直接喂给 select），
         * 而后端从不按渠道分支、全仓没有一处给推送发短信 —— 选了就落库，然后什么都不发生。
         * 现在两边都只有两档，这一格钉住「别再加回来」。
         */
        var badCh = post("/api/user/push-messages", Map.of(
                "title", MARK, "content", "负例", "channel", "SMS", "audienceType", "ALL"), t);
        assertThat(badCh.status).as("channel=SMS 竟然被接受了：%s", badCh.msg()).isEqualTo(400);

        var badAud = post("/api/user/push-messages", Map.of(
                "title", MARK, "content", "负例", "channel", "APP_PUSH",
                "audienceType", "EVERYONE"), t);
        assertThat(badAud.status).as("audienceType=EVERYONE 竟然被接受了：%s", badAud.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ coupon_tpl 的 type / status 非法值 400")
    void coupon_tpl_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/user/coupons", Map.of(
                "name", MARK, "type", "CUT", "value", "5", "currency", "AED", "status", "ACTIVE"), t);
        assertThat(ok.status).as("合法值应当能存：%s", ok.msg()).isEqualTo(200);

        var badType = post("/api/user/coupons", Map.of(
                "name", MARK, "type", "FREEBIE", "value", "5", "currency", "AED", "status", "ACTIVE"), t);
        assertThat(badType.status).as("type=FREEBIE 竟然被接受了：%s", badType.msg()).isEqualTo(400);

        var badStatus = post("/api/user/coupons", Map.of(
                "name", MARK, "type", "CUT", "value", "5", "currency", "AED", "status", "ENABLED"), t);
        assertThat(badStatus.status)
                .as("status=ENABLED 竟然被接受了——那是 agt_agent 的词表，不是这一列的：%s", badStatus.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★ notify_blacklist.reason 非法值 400——原因是合规凭据，写错等于给退订的人合法地再发")
    void blacklist_reason_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/platform/notify-blacklist", Map.of(
                "target", "0500000001", "channel", "SMS", "reason", "USER_OPT_OUT"), t);
        assertThat(ok.status).as("合法 reason 应当能存：%s", ok.msg()).isEqualTo(200);

        var bad = post("/api/platform/notify-blacklist", Map.of(
                "target", "0500000002", "channel", "SMS", "reason", "SPAMMY"), t);
        assertThat(bad.status).as("reason=SPAMMY 竟然被接受了：%s", bad.msg()).isEqualTo(400);
    }
}
