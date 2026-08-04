package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.trade.price.engine.ChargeChain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取价链后三步（[TDD-核心业务逻辑 §1.2]）。
 *
 * <p>本类锁的是**顺序**而非数值 —— 顺序写错不会抛异常、不会有任何告警，
 * 只会静默地多收或少收钱，等到对账时才暴露且无法逐单追溯。
 */
class ChargeChainTest {

    private final ChargeChain chain = new ChargeChain();

    // ─────────── 约束一：券在封顶之后 ───────────

    /**
     * 应收 40、封顶 30、券 10 → 应付 20。
     *
     * <p>若顺序反了（先抵扣再封顶）会得到 {@code min(40-10, 30) = 30}，
     * **用户白拿了这张券**。两种顺序都能跑通、都不报错，差别只有 10 块钱。
     */
    @Test
    void coupon_applies_after_cap_not_before() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("40"), null,
                new BigDecimal("10"), new BigDecimal("30"), null);

        assertThat(c.beforeDiscount()).as("封顶后、抵扣前").isEqualByComparingTo("30.00");
        assertThat(c.couponUsed()).isEqualByComparingTo("10");
        assertThat(c.payable()).as("30 - 10 = 20；若先抵扣再封顶会错成 30").isEqualByComparingTo("20.00");
    }

    /** 券面额大于应收时按应收抵，不产生负数应付。 */
    @Test
    void coupon_never_produces_negative_payable() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("8"), null,
                new BigDecimal("20"), null, null);

        assertThat(c.couponUsed()).as("只抵掉实际应收").isEqualByComparingTo("8.00");
        assertThat(c.payable()).isEqualByComparingTo("0.00");
    }

    // ─────────── 约束二：免单先算后减 ───────────

    /**
     * 免单不是「跳过计费」，是「算出来再减免」。
     *
     * <p>直接把 fee 置 0 的话，{@code waivedAmount} 就是 0，
     * **「本月减免总额」这个统计口径永远拿不到数** —— 运营无法回答
     * 「免费单让我们让利了多少」。
     */
    @Test
    void free_order_records_what_would_have_been_charged() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("24"), null, null, null, "VIP");

        assertThat(c.payable()).isEqualByComparingTo("0.00");
        assertThat(c.waivedAmount())
                .as("免单必须留下「本应收多少」，否则减免统计拿不到数")
                .isEqualByComparingTo("24.00");
    }

    /** 免单叠加券时，减免额是**券抵扣之后**的余额 —— 避免把券的部分重复计入让利。 */
    @Test
    void waived_amount_excludes_what_the_coupon_already_covered() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("30"), null,
                new BigDecimal("10"), null, "BD_DEMO");

        assertThat(c.couponUsed()).isEqualByComparingTo("10");
        assertThat(c.waivedAmount())
                .as("券已抵 10，平台实际让利 20，不该记 30")
                .isEqualByComparingTo("20.00");
        assertThat(c.payable()).isEqualByComparingTo("0.00");
    }

    // ─────────── 分时倍率与买断 ───────────

    /** 倍率在封顶之前生效 —— 高峰期涨价后仍受封顶保护。 */
    @Test
    void multiplier_applies_before_cap() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("20"), new BigDecimal("2"),
                null, new BigDecimal("30"), null);

        assertThat(c.payable()).as("20×2=40，封顶 30").isEqualByComparingTo("30.00");
        assertThat(c.buyout()).as("触及总封顶即买断").isTrue();
    }

    /** 未触顶不算买断 —— 买断会让充电宝转 SOLD，误判的代价是资产被错误核销。 */
    @Test
    void not_reaching_cap_is_not_buyout() {
        ChargeChain.Charged c = chain.charge(new BigDecimal("12"), null, null,
                new BigDecimal("30"), null);

        assertThat(c.buyout()).isFalse();
        assertThat(c.payable()).isEqualByComparingTo("12.00");
    }
}
