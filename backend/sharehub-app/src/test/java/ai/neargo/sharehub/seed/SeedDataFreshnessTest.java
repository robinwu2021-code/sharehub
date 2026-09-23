package ai.neargo.sharehub.seed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示数据必须是**新鲜的**。
 *
 * <p>此前 {@code SeedData.BASE} 写死为 2026-07-11：不论哪天灌库，订单/告警/工单全落在那一天附近。
 * 于是演示环境一打开，经营看板与站点概览的「近 7 日」「近 30 日」**全是 0** ——
 * 趋势一条平线、排行全零。数据明明有，页面却像坏了，而真相只是这批数据太老。
 *
 * <p>这条用例守的就是这件事：**别再把时间基准写成常量**。
 * 它不检查具体数值，只检查「相对今天有多远」—— 那正是唯一会让演示失效的性质。
 *
 * <p>放在 {@code ai.neargo.sharehub.seed} 包下，是因为 {@code SeedData#init()} 是包级私有：
 * **不为了测试把生产代码的可见性放宽** —— 那等于让任何人都能在运行时重灌一遍种子。
 */
class SeedDataFreshnessTest {

    /** 看板与概览最长的默认窗口是 90 天；演示订单至少要有一部分落在 7 天内才看得出趋势。 */
    private static final int RECENT_DAYS = 7;

    @Test
    void demo_orders_land_in_the_recent_window() {
        SeedData seed = new SeedData();
        seed.init();

        var settled = seed.orders().stream()
                .filter(o -> o.rentEndAt() != null && o.feeAmount() > 0)
                .toList();
        assertThat(settled).as("种子里应有已结算且有金额的订单").isNotEmpty();

        Instant now = Instant.now();
        long recent = settled.stream()
                .filter(o -> Duration.between(Instant.parse(o.rentEndAt()), now).toDays() <= RECENT_DAYS)
                .count();
        assertThat(recent)
                .as("近 %d 日内应有已结算订单，否则演示环境的概览与看板会全是 0（时间基准又被写死了？）",
                        RECENT_DAYS)
                .isPositive();

        // 也不能全挤在今天：趋势图需要多天分布才画得出形状
        long distinctDays = settled.stream().map(o -> o.rentEndAt().substring(0, 10)).distinct().count();
        assertThat(distinctDays).as("演示订单要跨多天，否则趋势图只有一根柱子").isGreaterThan(1);
    }
}
