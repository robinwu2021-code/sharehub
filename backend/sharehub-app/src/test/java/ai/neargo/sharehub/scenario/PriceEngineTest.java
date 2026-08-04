package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.trade.price.engine.PriceEngine;
import ai.neargo.sharehub.trade.price.engine.PriceItemSpec;
import ai.neargo.sharehub.trade.price.engine.PriceItemSpec.PriceLadderSpec;
import ai.neargo.sharehub.trade.price.engine.PriceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计价引擎（ADR-018 · M2）。
 *
 * <p><b>第一组是影子重算</b>：新引擎必须逐分复现旧的硬编码计费
 * {@code Math.min(30, ceil(min/30) * 3)}。**复现不了就不许替换** ——
 * 计价改造最大的风险不是算不出来，是算得不一样却没人发现。
 *
 * <p>本类是纯单测（不起 Spring 上下文）：引擎是纯函数，没有依赖，
 * 起上下文只会让这些用例慢 8 秒且更难定位失败。
 */
class PriceEngineTest {

    private final PriceEngine engine = new PriceEngine();

    /** 旧的硬编码计费，作为影子重算的基准。 */
    private static double legacyFee(long minutes) {
        return Math.min(30.0, Math.ceil(minutes / 30.0) * 3.0);
    }

    /** 现存充电宝方案的等价规格：每 30 分钟 3 元，日封顶 30，向上取整，无免费额度。 */
    private static PriceItemSpec powerbankSpec() {
        return new PriceItemSpec("TIME_FEE", "MINUTE",
                BigDecimal.ZERO, new BigDecimal("30"), "CEIL",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, null, new BigDecimal("3"))),
                new BigDecimal("30"));
    }

    private BigDecimal priceMinutes(long minutes) {
        return engine.price(List.of(powerbankSpec()),
                Map.of("MINUTE", BigDecimal.valueOf(minutes)), "AED").total();
    }

    // ─────────────── 影子重算 ───────────────

    /**
     * 新引擎与旧硬编码在 0–2000 分钟全区间逐分一致。
     *
     * <p>覆盖了首分钟、步长边界（30/60/90…）、封顶点（300 分钟起恒为 30）等所有特殊点，
     * 不需要人工挑样本 —— 挑样本容易漏掉恰好出错的那个值。
     */
    @Test
    void engine_reproduces_legacy_hardcoded_fee_for_every_minute() {
        for (long m = 0; m <= 2000; m++) {
            assertThat(priceMinutes(m).doubleValue())
                    .as("第 %d 分钟：新引擎应与旧硬编码一致", m)
                    .isEqualTo(legacyFee(m));
        }
    }

    // ─────────────── 分段累加（阶梯的核心语义）───────────────

    /**
     * 阶梯是<b>分段累加</b>，不是「落在哪档就整体按哪档」。
     *
     * <p>0–100 度 0.5、100 度以上 0.8，充 150 度：
     * 分段累加 = 100×0.5 + 50×0.8 = 90；整体套档 = 150×0.8 = 120。
     * 两种口径差 33%，电价/阶梯水电/流量包用的都是前者。
     */
    @Test
    void ladders_accumulate_by_segment_not_whole_amount() {
        PriceItemSpec energy = new PriceItemSpec("ENERGY_FEE", "KWH",
                BigDecimal.ZERO, BigDecimal.ONE, "HALF_UP",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0.5")),
                        new PriceLadderSpec(new BigDecimal("100"), null, new BigDecimal("0.8"))),
                null);

        BigDecimal total = engine.price(List.of(energy),
                Map.of("KWH", new BigDecimal("150")), "AED").total();

        assertThat(total).isEqualByComparingTo("90.00");
        assertThat(total).as("不应是整体套高档的 120").isNotEqualByComparingTo("120.00");
    }

    // ─────────────── 电费与服务费分列 ───────────────

    /**
     * 充电桩一单同时产出电费与服务费，且<b>逐项可见</b>。
     *
     * <p>分列是硬要求：多数市场电费不可加价、服务费才是平台收入，
     * 发票、分润、对账三处口径都要求它们分开。只返回合计的话这些都要再算一遍。
     */
    @Test
    void charging_order_yields_separate_energy_and_service_lines() {
        PriceItemSpec energy = new PriceItemSpec("ENERGY_FEE", "KWH",
                BigDecimal.ZERO, BigDecimal.ONE, "HALF_UP",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, null, new BigDecimal("0.6"))), null);
        PriceItemSpec service = new PriceItemSpec("SERVICE_FEE", "KWH",
                BigDecimal.ZERO, BigDecimal.ONE, "HALF_UP",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, null, new BigDecimal("0.2"))), null);

        PriceResult r = engine.price(List.of(energy, service),
                Map.of("KWH", new BigDecimal("20")), "AED");

        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0).itemType()).isEqualTo("ENERGY_FEE");
        assertThat(r.lines().get(0).amount()).isEqualByComparingTo("12.00");
        assertThat(r.lines().get(1).itemType()).isEqualTo("SERVICE_FEE");
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("4.00");
        assertThat(r.total()).isEqualByComparingTo("16.00");
    }

    // ─────────────── 免费额度与封顶 ───────────────

    /** 免费额度先扣：宽限 15 分钟，用 10 分钟不收费、用 45 分钟只按 30 分钟计。 */
    @Test
    void free_quota_is_deducted_before_metering() {
        PriceItemSpec idle = new PriceItemSpec("IDLE_FEE", "MINUTE",
                new BigDecimal("15"), BigDecimal.ONE, "CEIL",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, null, BigDecimal.ONE)), null);

        assertThat(engine.price(List.of(idle), Map.of("MINUTE", new BigDecimal("10")), "AED").total())
                .as("未超宽限期不收费").isEqualByComparingTo("0.00");
        assertThat(engine.price(List.of(idle), Map.of("MINUTE", new BigDecimal("45")), "AED").total())
                .as("45 分钟扣掉 15 分钟宽限，按 30 分钟计").isEqualByComparingTo("30.00");
    }

    /** 封顶后要能说明「为什么少收了」—— cappedBy 不能是 null。 */
    @Test
    void capped_line_records_the_cap_for_explainability() {
        PriceResult r = engine.price(List.of(powerbankSpec()),
                Map.of("MINUTE", new BigDecimal("1000")), "AED");

        assertThat(r.total()).isEqualByComparingTo("30.00");
        assertThat(r.lines().get(0).cappedBy())
                .as("被封顶时必须留下封顶值，否则账单无法解释")
                .isEqualByComparingTo("30");
    }

    // ─────────────── 口径必须显式 ───────────────

    /**
     * 不认识的舍入口径<b>必须抛异常</b>，不能默默取默认。
     *
     * <p>静默按错误口径收钱，差异会一直累积到对账时才暴露，届时已无法逐单追溯。
     */
    @Test
    void unknown_rounding_mode_fails_loudly() {
        PriceItemSpec bad = new PriceItemSpec("TIME_FEE", "MINUTE",
                BigDecimal.ZERO, BigDecimal.ONE, "BANKERS_ROUNDING",
                List.of(new PriceLadderSpec(BigDecimal.ZERO, null, BigDecimal.ONE)), null);

        assertThatThrownBy(() -> engine.price(List.of(bad), Map.of("MINUTE", BigDecimal.TEN), "AED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的舍入口径");
    }
}
