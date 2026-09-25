package ai.neargo.sharehub.alarm.job;

import ai.neargo.sharehub.alarm.dto.AlarmDtos.TickResult;
import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.alarm.profile.SiteProfileService;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 业务告警的两个定时任务（TDD/05 §九 内部表）。 */
public final class AlarmJobs {

    private AlarmJobs() {
    }

    /** 每分钟：STATE 判定 · 到期处置 · 恢复关闭 · 锁仓兜底。 */
    @Component
    public static class AlarmRuleEvalJob implements JobHandler {
        private final AlarmEngine engine;

        public AlarmRuleEvalJob(AlarmEngine engine) {
            this.engine = engine;
        }

        @Override
        public String name() {
            return "alarm-rule-eval";
        }

        @Override
        public JobResult run(JobInvocation invocation) {
            TickResult r = engine.tick(LocalDateTime.now());
            return r.opened() + r.recovered() + r.closed() + r.disposed() == 0
                    ? JobResult.skipped("无变化")
                    : JobResult.success("opened=" + r.opened() + " recovered=" + r.recovered() + " closed=" + r.closed()
                    + " disposed=" + r.disposed());
        }
    }

    /** 每日：站点画像（等级 · 高峰时段）重算。 */
    @Component
    public static class AlarmSiteProfileJob implements JobHandler {
        private final SiteProfileService profiles;

        public AlarmSiteProfileJob(SiteProfileService profiles) {
            this.profiles = profiles;
        }

        @Override
        public String name() {
            return "alarm-site-profile";
        }

        @Override
        public JobResult run(JobInvocation invocation) {
            int n = profiles.recompute();
            return n == 0 ? JobResult.skipped("无营业站点") : JobResult.success("sites=" + n);
        }
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration alarmRuleEvalDeclaration() {
            return JobDeclaration.of("alarm-rule-eval", "业务告警判定", "0 * * * * *")
                    .ownerModule("ops.alarm").timeoutSec(50).lockAtMostSec(55).build();
        }

        @Bean
        JobDeclaration alarmSiteProfileDeclaration() {
            return JobDeclaration.of("alarm-site-profile", "告警站点画像", "0 20 3 * * *")
                    .ownerModule("ops.alarm").timeoutSec(600).lockAtMostSec(900).build();
        }
    }
}
