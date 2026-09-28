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
        jdbc.update("DELETE FROM usr_free_whitelist WHERE c_user_no LIKE 'UVOCAB%'");
        jdbc.update("DELETE FROM notify_template WHERE name = ?", MARK);
        jdbc.update("DELETE FROM md_problem WHERE title = ?", MARK);
        // 发票有明细子表，先删子后删主 —— 反过来留下孤儿明细行
        jdbc.update("DELETE FROM fin_invoice_item WHERE invoice_no IN (SELECT invoice_no FROM fin_invoice WHERE payee_name = ?)", MARK);
        jdbc.update("DELETE FROM fin_invoice WHERE payee_name = ?", MARK);
        jdbc.update("DELETE FROM iam_dept WHERE name = ?", MARK);
        jdbc.update("DELETE FROM dev_code_batch WHERE range_start LIKE 'VOCABPROBE%'");
        jdbc.update("DELETE FROM loc_lead WHERE venue_name = ?", MARK);
        jdbc.update("DELETE FROM iam_employee WHERE name = ?", MARK);
        jdbc.update("DELETE FROM usr_invoice_title WHERE title = ?", MARK);
        jdbc.update("DELETE FROM dev_ota_rollout WHERE fw_version LIKE 'VOCAB%'");
        // ⚠️ 按 fw_version 删，不是 version —— 实体的 `version` 字段落在 `fw_version` 列上
        // （`version` 是 BIGINT 死列，见 V141）。第一版写成 version，MySQL 把它当数字比，
        // **一行都没删掉还不报错**，是姊妹卡口 StoredValueInVocabularyTest 红了才发现的。
        jdbc.update("DELETE FROM dev_ota_release WHERE fw_version LIKE 'VOCAB%'");
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
    @DisplayName("★ usr_free_whitelist 的 reason / quota_type 非法值 400——额度类型错了就是免单口径错")
    void free_whitelist_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/user/free-whitelist", Map.of(
                "cUserNo", "UVOCAB1", "reason", "INTERNAL_TEST", "quotaType", "TIMES",
                "quotaValue", 3), t);
        assertThat(ok.status).as("合法值应当能存：%s", ok.msg()).isEqualTo(200);

        var badReason = post("/api/user/free-whitelist", Map.of(
                "cUserNo", "UVOCAB2", "reason", "BECAUSE", "quotaType", "TIMES",
                "quotaValue", 3), t);
        assertThat(badReason.status).as("reason=BECAUSE 竟然被接受了：%s", badReason.msg()).isEqualTo(400);

        // quotaType 决定 quota 怎么解释（次数 / 金额 / 不限）。写错就是免单口径错，而那是钱
        var badQuota = post("/api/user/free-whitelist", Map.of(
                "cUserNo", "UVOCAB3", "reason", "INTERNAL_TEST", "quotaType", "FOREVER",
                "quotaValue", 3), t);
        assertThat(badQuota.status).as("quotaType=FOREVER 竟然被接受了：%s", badQuota.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ notify_template / notify_blacklist 的 channel 非法值 400——四套 channel 词表各自为界")
    void notify_channels_reject_out_of_vocabulary() {
        String t = login("ADMIN");

        /*
         * 这两列的词表**不一样**：模板是 INAPP/PUSH/SMS/SUBSCRIBE，拉黑是
         * ALL/EMAIL/PUSH/SMS/WHATSAPP。所以两格各用对方的独有值做负例 ——
         * 一旦哪天有人把它们合并成一个枚举，这两格会同时红。
         */
        var okTpl = post("/api/platform/notify-templates", Map.of(
                "templateNo", "", "name", MARK, "channel", "SMS", "title", MARK, "content", MARK), t);
        assertThat(okTpl.status).as("模板合法 channel 应当能存：%s", okTpl.msg()).isEqualTo(200);

        var badTpl = post("/api/platform/notify-templates", Map.of(
                "name", MARK, "channel", "WHATSAPP", "title", MARK, "content", MARK), t);
        assertThat(badTpl.status)
                .as("模板 channel=WHATSAPP 被接受了——那是拉黑的词表，不是模板的：%s", badTpl.msg())
                .isEqualTo(400);

        var badBl = post("/api/platform/notify-blacklist", Map.of(
                "target", "0500000003", "channel", "SUBSCRIBE", "reason", "MANUAL"), t);
        assertThat(badBl.status)
                .as("拉黑 channel=SUBSCRIBE 被接受了——那是模板的词表，不是拉黑的：%s", badBl.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★ fin_invoice.status 非法值 400 —— 此前 req.status() 原样落库，而发票是税务单据")
    void invoice_status_rejects_out_of_vocabulary() {
        String t = login("FINANCE");

        var ok = post("/api/trade/invoices", Map.of(
                "payeeType", "AGENT", "payeeNo", "AG0002", "payeeName", MARK,
                "amount", "100", "currency", "AED", "status", "DRAFT"), t);
        assertThat(ok.status).as("合法 status 应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * 串一个字母的后果不是报错，是**这张发票从此按状态筛不出来** ——
         * 开票方以为开了，对账方一条都查不到，两边都不报错。
         */
        var bad = post("/api/trade/invoices", Map.of(
                "payeeType", "AGENT", "payeeNo", "AG0002", "payeeName", MARK,
                "amount", "100", "currency", "AED", "status", "ISUED"), t);
        assertThat(bad.status).as("status=ISUED 竟然被接受了：%s", bad.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 发票 status 留空 = DRAFT，小写 issued 归一成 ISSUED —— 校验管的是值，不是必填")
    void invoice_status_defaults_and_normalises() {
        String t = login("FINANCE");

        var blank = post("/api/trade/invoices", Map.of(
                "payeeType", "AGENT", "payeeNo", "AG0002", "payeeName", MARK,
                "amount", "100", "currency", "AED"), t);
        assertThat(blank.status).as("没传 status 被拒了 —— 那是把必填和合法性搞混了：%s", blank.msg())
                .isEqualTo(200);
        assertThat(blank.okData().path("invoice").path("status").asText()).isEqualTo("DRAFT");

        var lower = post("/api/trade/invoices", Map.of(
                "payeeType", "AGENT", "payeeNo", "AG0002", "payeeName", MARK,
                "amount", "100", "currency", "AED", "status", "issued"), t);
        assertThat(lower.okData().path("invoice").path("status").asText())
                .as("小写没归一 —— 库里就会同时有 issued 和 ISSUED，按状态筛只筛得到一半")
                .isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("★ iam_dept.status 非法值 400 —— 实体当请求体，这一列原先原样落库")
    void dept_status_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/platform/departments", Map.of("name", MARK, "status", "ACTIVE"), t);
        assertThat(ok.status).as("合法 status 应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * 停用的部门不出现在「上级部门」候选里。写错一个字母之后它**既不是启用也不是停用**，
         * 两边的筛选都落空 —— 组织树上它还在，而谁也选不到它。
         */
        var bad = post("/api/platform/departments", Map.of("name", MARK, "status", "DISABLE"), t);
        assertThat(bad.status).as("status=DISABLE 竟然被接受了：%s", bad.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ dev_code_batch 的 status / code_type 非法值 400 —— 编辑路径此前连 beforeUpdate 都没有")
    void code_batch_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/ops/device-code-batches", Map.of(
                "vendorCode", "DEMO", "codeType", "QR",
                "rangeStart", "VOCABPROBE0001", "rangeEnd", "VOCABPROBE0009",
                "total", 9, "status", "PENDING"), t);
        assertThat(ok.status).as("合法值应当能存：%s", ok.msg()).isEqualTo(200);

        // 两列都在 filterFields 里 —— 写错就是这批码从列表筛不出来，
        // 于是有人再建一批，号段跟丢掉的那批重叠
        var badType = post("/api/ops/device-code-batches", Map.of(
                "vendorCode", "DEMO", "codeType", "BARCODE",
                "rangeStart", "VOCABPROBE1001", "rangeEnd", "VOCABPROBE1009", "total", 9), t);
        assertThat(badType.status).as("codeType=BARCODE 竟然被接受了：%s", badType.msg()).isEqualTo(400);

        var badStatus = post("/api/ops/device-code-batches", Map.of(
                "vendorCode", "DEMO", "codeType", "QR",
                "rangeStart", "VOCABPROBE2001", "rangeEnd", "VOCABPROBE2009",
                "total", 9, "status", "DONE"), t);
        assertThat(badStatus.status).as("status=DONE 竟然被接受了：%s", badStatus.msg()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ loc_lead.share_mode 非法值 400 —— 谈判条款会原样抄进合同")
    void lead_share_mode_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/ops/leads", Map.of(
                "venueName", MARK, "contact", "0500000009", "shareMode", "GUARANTEE"), t);
        assertThat(ok.status).as("合法 shareMode 应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * 负例刻意用 LEDGER —— 它是 ShareMode（结算路径）的词表，不是
         * ContractShareMode（商业模式）的。两个枚举同名不同物，
         * 哪天有人把它们合并，这一格会红。
         */
        var bad = post("/api/ops/leads", Map.of(
                "venueName", MARK, "contact", "0500000009", "shareMode", "LEDGER"), t);
        assertThat(bad.status)
                .as("shareMode=LEDGER 被接受了 —— 那是结算路径的词表，不是商业模式的：%s", bad.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★ iam_employee.status 非法值 400 —— 写错一个字母，这个人既登不上也不算离职")
    void employee_status_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/platform/employees", Map.of("name", MARK, "status", "ACTIVE"), t);
        assertThat(ok.status).as("合法 status 应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * 登录与授权都按 ACTIVE 判。负例用 INACTIVE：那是别处的「开关」词表，
         * 本列是 ACTIVE/LEFT（在职/离职）。串错之后运营端看着他还在，而他自己进不来。
         */
        var bad = post("/api/platform/employees", Map.of("name", MARK, "status", "INACTIVE"), t);
        assertThat(bad.status)
                .as("status=INACTIVE 被接受了 —— 本列词表是 ACTIVE/LEFT：%s", bad.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★★ usr_invoice_title.type：小写 company 原先**绕过税号必填**，现在归一后照样拦")
    void invoice_title_type_rejects_and_normalises() {
        String token = consumerToken();

        var ok = post("/mp/user/invoice-titles", Map.of(
                "type", "PERSONAL", "title", MARK), token);
        assertThat(ok.status).as("个人抬头无需税号：%s", ok.msg()).isEqualTo(200);

        var bad = post("/mp/user/invoice-titles", Map.of(
                "type", "ORGANISATION", "title", MARK), token);
        assertThat(bad.status).as("type=ORGANISATION 竟然被接受了：%s", bad.msg()).isEqualTo(400);

        /*
         * ★ 这一格才是重点：判税号必填的那行原先写的是 "COMPANY".equals(type)，
         * 于是传小写 company **既没报错也没要税号** —— 抬头存下来了，
         * 而按它开出去的发票是废的。归一之后小写照样走到税号必填。
         */
        var lower = post("/mp/user/invoice-titles", Map.of(
                "type", "company", "title", MARK), token);
        assertThat(lower.status)
                .as("小写 company 绕过了税号必填 —— 那正是这一格要拦的：%s", lower.msg())
                .isEqualTo(400);

        var withTrn = post("/mp/user/invoice-titles", Map.of(
                "type", "company", "title", MARK, "vatTrn", "100000000000003"), token);
        assertThat(withTrn.status).as("带税号的企业抬头应当能存：%s", withTrn.msg()).isEqualTo(200);
        assertThat(withTrn.okData().path("type").asText())
                .as("小写没归一 —— 库里会同时有 company 和 COMPANY").isEqualTo("COMPANY");
    }

    /** C 端令牌：dev-mode 下 OTP 明文回传，见 ConsumerCouponClaimTest 的同名夹具。 */
    private String consumerToken() {
        String phone = "0599" + String.valueOf(System.currentTimeMillis()).substring(7);
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ OTA 发布/投放的四列 —— 原先只在空的时候补默认值，传了错的照样落库")
    void ota_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var okRel = post("/api/ops/ota-releases", Map.of(
                "fwType", "CABINET", "vendorCode", "DEMO", "version", "VOCAB1.0.0",
                "versionCode", 100, "status", "DRAFT"), t);
        assertThat(okRel.status).as("合法发布单应当能存：%s", okRel.msg()).isEqualTo(200);
        String releaseNo = okRel.okData().path("releaseNo").asText();

        var badRel = post("/api/ops/ota-releases", Map.of(
                "fwType", "CABINET", "vendorCode", "DEMO", "version", "VOCAB1.0.1",
                "versionCode", 101, "status", "RELEASED"), t);
        assertThat(badRel.status)
                .as("status=RELEASED 被接受了 —— 本列词表是 DRAFT/PUBLISHED/PAUSED/COMPLETED/ARCHIVED：%s",
                        badRel.msg())
                .isEqualTo(400);

        var okRo = post("/api/ops/ota-rollouts", Map.of(
                "releaseNo", releaseNo, "fwVersion", "VOCAB1.0.0",
                "strategy", "GRAY", "scope", "ALL"), t);
        assertThat(okRo.status).as("合法投放单应当能存：%s", okRo.msg()).isEqualTo(200);

        /*
         * scope 写错 = 这次投放打给了错的一批设备；strategy 写错 = 本该灰度的一次全量推了出去。
         * 固件是刷进硬件里的 —— 发现的时候已经刷完了，没有「撤回」这一说。
         */
        var badScope = post("/api/ops/ota-rollouts", Map.of(
                "releaseNo", releaseNo, "fwVersion", "VOCAB1.0.0", "scope", "CABINET"), t);
        assertThat(badScope.status).as("scope=CABINET 竟然被接受了：%s", badScope.msg()).isEqualTo(400);

        var badStrategy = post("/api/ops/ota-rollouts", Map.of(
                "releaseNo", releaseNo, "fwVersion", "VOCAB1.0.0", "strategy", "CANARY"), t);
        assertThat(badStrategy.status).as("strategy=CANARY 竟然被接受了：%s", badStrategy.msg()).isEqualTo(400);

        var badRoStatus = post("/api/ops/ota-rollouts", Map.of(
                "releaseNo", releaseNo, "fwVersion", "VOCAB1.0.0", "status", "STARTED"), t);
        assertThat(badRoStatus.status)
                .as("创建时 status=STARTED 被接受了 —— 状态机只拦编辑路径的迁移，拦不住创建：%s",
                        badRoStatus.msg())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("★ md_problem 的 category / suggested_action 非法值 400——后者决定客服怎么路由")
    void problem_catalog_rejects_out_of_vocabulary() {
        String t = login("ADMIN");

        var ok = post("/api/platform/problems", Map.of(
                "title", MARK, "category", "DEVICE", "suggestedAction", "TO_WORKORDER",
                "status", "ENABLED"), t);
        assertThat(ok.status).as("合法值应当能存：%s", ok.msg()).isEqualTo(200);

        /*
         * status 是这张表的第三列词表，2026-09-28 才接上。C 端 FAQ 按
         * status='ENABLED' 筛 —— 写错一个字母，这条问题从用户那边消失，
         * 而运营端列表里它好好地在，两边都不报错。
         * 负例用 ACTIVE：那是别处的「开关」词表，本列是 ENABLED/DISABLED。
         */
        var badStatus = post("/api/platform/problems", Map.of(
                "title", MARK, "category", "DEVICE", "suggestedAction", "TO_WORKORDER",
                "status", "ACTIVE"), t);
        assertThat(badStatus.status)
                .as("status=ACTIVE 被接受了 —— 本列词表是 ENABLED/DISABLED：%s", badStatus.msg())
                .isEqualTo(400);

        var badCat = post("/api/platform/problems", Map.of(
                "title", MARK, "category", "HARDWARE", "suggestedAction", "TO_WORKORDER"), t);
        assertThat(badCat.status).as("category=HARDWARE 竟然被接受了：%s", badCat.msg()).isEqualTo(400);

        // suggestedAction 决定这条问题走自助/客服/退款/工单哪一条路，错了就是路由错
        var badAct = post("/api/platform/problems", Map.of(
                "title", MARK, "category", "DEVICE", "suggestedAction", "TO_MANAGER"), t);
        assertThat(badAct.status).as("suggestedAction=TO_MANAGER 竟然被接受了：%s", badAct.msg()).isEqualTo(400);
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
