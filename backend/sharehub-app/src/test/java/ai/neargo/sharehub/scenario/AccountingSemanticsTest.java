package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个记账语义的口径守卫（未完成清单 B1 → V34 落地）。
 *
 * <p>这三条都不是「功能能不能跑」，而是<b>数字对不对</b>。它们一旦漂了，
 * 页面照常渲染、接口照常 200，只有对账时才会发现结算金额不对 —— 所以必须有断言钉住。
 */
class AccountingSemanticsTest extends ApiTestSupport {

    /**
     * 分润统计的 GMV 取<b>基数快照</b>，不由 {@code amount / rate} 反推。
     *
     * <p>反推在固定额分润（rate=0）上会让整行消失、在阶梯分润上会偏差。
     * 断言方式不写死数字：只要求「统计 GMV = 明细 gross_amount 之和」这条结构性关系成立。
     */
    @Test
    void share_summary_gmv_comes_from_snapshot_not_derived_from_rate() {
        String token = login("FINANCE");
        JsonNode records = get("/api/trade/share-records?page=1&size=200", token).okData();
        if (records.path("total").asLong() == 0) return;   // 无分润数据时该断言无意义

        BigDecimal grossSum = BigDecimal.ZERO;
        for (JsonNode r : records.path("list")) {
            assertThat(r.has("period")).as("明细必须带归属账期（V34）").isTrue();
            JsonNode gross = r.path("grossAmount");
            if (!gross.isNull()) grossSum = grossSum.add(gross.decimalValue());
        }

        BigDecimal summaryGmv = BigDecimal.ZERO;
        for (JsonNode s : get("/api/trade/share-summaries?page=1&size=200", token).okData().path("list")) {
            summaryGmv = summaryGmv.add(s.path("gmv").decimalValue());
        }
        assertThat(summaryGmv).as("统计 GMV 应等于明细基数快照之和（同一份事实的两种折法）")
                .isEqualByComparingTo(grossSum);
    }

    /**
     * 归属账期是<b>列</b>而不是由 {@code created_at} 现推。
     *
     * <p>用「按 period 过滤能筛出结果、且筛出来的每行 period 都等于该值」验证 ——
     * 现推实现下，跨月补记的分润会被筛进错误的账期。
     */
    @Test
    void share_records_carry_their_own_period() {
        String token = login("FINANCE");
        JsonNode all = get("/api/trade/share-summaries?page=1&size=1", token).okData();
        if (all.path("total").asLong() == 0) return;

        String period = all.path("list").get(0).path("period").asText();
        assertThat(period).as("统计行必须给出账期").matches("\\d{4}-\\d{2}");

        JsonNode filtered = get("/api/trade/share-summaries?page=1&size=50&period=" + period, token).okData();
        assertThat(filtered.path("total").asLong()).as("按账期过滤应能筛出该账期的行").isPositive();
        for (JsonNode s : filtered.path("list")) {
            assertThat(s.path("period").asText()).isEqualTo(period);
        }
    }

    /**
     * 凭证借贷必须平衡 —— 平衡判定由<b>服务端</b>给出，不让前端自己加。
     *
     * <p>两端各算一次的后果是「页面说平了、后端说没平」，而这种分歧在财务场景里
     * 没有任何一方是可信的。
     */
    @Test
    void voucher_balance_is_decided_by_server() {
        String token = login("FINANCE");
        JsonNode ledger = get("/api/trade/ledger?page=1&size=1", token).okData();
        if (ledger.path("total").asLong() == 0) return;

        String voucherNo = ledger.path("list").get(0).path("voucherNo").asText();
        JsonNode v = get("/api/trade/ledger/vouchers/" + voucherNo, token).okData();
        assertThat(v.has("balanced")).as("平衡判定必须由服务端出参给出").isTrue();
        assertThat(v.path("balanced").asBoolean()).as("种子凭证应借贷平衡").isTrue();
        assertThat(v.path("debit").decimalValue())
                .as("借方合计应等于贷方合计")
                .isEqualByComparingTo(v.path("credit").decimalValue());
    }
}
