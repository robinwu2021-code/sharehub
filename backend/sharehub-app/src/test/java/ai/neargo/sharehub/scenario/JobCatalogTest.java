package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobRegistry;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务目录：**六个 A 类任务真的注册上了，而且真的能跑**。
 *
 * <p>{@code JobRegistryTest} 用的是手搓的假 handler，它验的是注册表的规则；
 * 本条用**真实 Spring 上下文**，验的是接线 —— 声明与实现在真容器里配得上、
 * 触发一次不炸。两者缺一不可：规则对而没接上，等于一个任务都不会跑。
 */
class JobCatalogTest extends ApiTestSupport {

    /** v4/07 §5.1 的 A 类（业务已实现、本轮包成 handler 的六个）。 */
    private static final List<String> A_CLASS = List.of(
            "outbox-dispatch",
            "price-adjust-apply",
            "push-sweep-due",
            "settlement-generate",
            "inspection-plan-run",
            "ownership-reconcile");

    @Autowired
    private JobRegistry registry;

    @Test
    @DisplayName("★ 六个 A 类任务都在注册表里（少一个 = 那件事没人做，而代码摆在那儿）")
    void all_six_are_registered() {
        assertThat(registry.declarations().stream().map(JobDeclaration::name))
                .containsAll(A_CLASS);
    }

    @Test
    @DisplayName("每个任务都有中文标题——运营端按标题显示，缺了那一行只剩一串英文任务名")
    void every_job_has_a_human_title() {
        for (JobDeclaration d : registry.declarations()) {
            assertThat(d.title()).as("任务 %s 的标题", d.name()).isNotBlank();
            assertThat(d.ownerModule()).as("任务 %s 的归属模块（运营端按它分组）", d.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("★★ 每个都能真的触发一次且不抛——空库上应为 SKIPPED 或 SUCCESS，不该是 FAILED")
    @Transactional   // 见方法内注释：这些任务会真写业务单据，测完必须回滚
    void every_job_actually_runs() {
        /*
         * 这一条抓的是「接线错了但编译通过」：注入了错的 bean、service 方法签名变了、
         * 事务/安全上下文在任务里拿不到……这些都不会在编译期暴露，
         * 而到了生产就是调度器每次来调都记 FAILED。
         *
         * 空库上大多数应返回 SKIPPED（没有到期数据），这正是预期 ——
         * SKIPPED 不是失败（见 JobResult 的类注释）。
         *
         * ⚠️ **必须 @Transactional 回滚**：这些是真任务，会真写业务单据。
         * 第一版没加，一次跑下来在共享测试库里生成了 4 张巡检工单（2 个启用计划 × 2 站），
         * 而且**每天跑每天造** —— test_sharehub 是累积型共享资产（见 CLAUDE.md），
         * 一条用例天天往里塞单据，下一个查「这些工单哪来的」的人会查很久。
         */
        /*
         * 遍历**注册表**而不是 A_CLASS 那张写死的表：以后每新增一个任务都自动被冒烟，
         * 不必记得回来改这条用例 —— 「要记得改的卡口」迟早有一次没人改。
         */
        for (String name : registry.declarations().stream().map(JobDeclaration::name).toList()) {
            JobResult r = registry.trigger(name);
            assertThat(r.status())
                    .as("任务 %s 触发失败：%s", name, r.error())
                    .isIn(JobResult.Status.SUCCESS, JobResult.Status.SKIPPED);
        }
    }

    @Test
    @DisplayName("★ 出账按月跑，不是按天——按天会让整月的分润沉底")
    void settlement_runs_monthly_not_daily() {
        /*
         * generate 的幂等键是 (payeeNo, period)：该账期出过单就整个跳过。
         * 于是「每天跑、账期取当月」＝ 每月 2 号出一张只覆盖 1 号的结算单，
         * 此后该账期的分润再也不会被结算，永远停在 PENDING —— 不报错，只是钱没结出去。
         * v4/07 §5.1 原先写的是 `0 0 2 * * *`（每天），这条卡住它别被改回去。
         */
        JobDeclaration d = registry.declarations().stream()
                .filter(x -> x.name().equals("settlement-generate")).findFirst().orElseThrow();
        assertThat(d.defaultCron())
                .as("出账 cron 必须是「每月 1 号」，按天跑会出错账（见 SettlementGenerateJob 类注释）")
                .isEqualTo("0 0 2 1 * *");
    }
}
