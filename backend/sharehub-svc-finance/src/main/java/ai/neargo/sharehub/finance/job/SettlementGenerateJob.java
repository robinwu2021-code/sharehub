package ai.neargo.sharehub.finance.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.finance.service.SettlementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 周期出账：把账期内 PENDING 的分润记录按收款方汇总成结算单。
 *
 * <h2>⚠️ 按月跑，不能按天跑 —— v4/07 §5.1 写的 {@code 0 0 2 * * *} 会出错账</h2>
 * {@link SettlementService#generate} 的幂等键是 <b>(payeeNo, period)</b>：
 * 该账期已出过单就整个跳过。于是「每天跑一次、账期取当月」会变成 ——
 * 每月 2 号出一张只覆盖 1 号那点记录的结算单，
 * <b>此后该账期的分润再也不会被结算</b>，它们永远停在 PENDING。
 * 不报错、不告警，只是钱没结出去，而结算单看着是有的。
 *
 * <p>所以固定为**每月 1 号跑上个月**：{@code bizDate} 是「昨天」（v4/07 §二），
 * 1 号的昨天正是上月最后一天，取它的 {@code YYYY-MM} 即上一账期。
 *
 * <p>{@code payeeType} 传 null = 不限收款方类型（VENUE 与 AGENT 一起出）。
 */
@Component
public class SettlementGenerateJob implements JobHandler {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SettlementService settlements;

    public SettlementGenerateJob(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public String name() {
        return "settlement-generate";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        String period = invocation.bizDate().format(PERIOD);
        List<String> created = settlements.generate(period, null);
        return created.isEmpty()
                ? JobResult.skipped("账期 " + period + " 没有待出账的分润（或已出过）")
                : JobResult.success("period=" + period + " created=" + created.size() + " " + created);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration settlementGenerateDeclaration() {
            // 每月 1 号 02:00；见类注释：按天跑会让整月的分润沉底
            return JobDeclaration.of("settlement-generate", "周期出账", "0 0 2 1 * *")
                    .ownerModule("finance")
                    .logEveryRun(true)          // 月频 + 涉及钱：每次都要留痕
                    .timeoutSec(120).lockAtMostSec(300)
                    .build();
        }
    }
}
