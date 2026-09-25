package ai.neargo.sharehub.user.marketing.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.user.marketing.service.PushService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 到点的定时推送出队发送。
 *
 * <p><b>这个任务不在 v4/07 §五 的目录里</b> —— 它是 2026-09-24 之后加的，
 * 当时目录没跟上（已于 9-25 补入）。手动端点
 * {@code POST /api/user/push-messages/sweep-due} 保留，不影响。
 *
 * <p>取「现在」而不是 {@link JobInvocation#bizDate()}：推送是**按时刻**排期的，
 * bizDate 是日粒度的业务日期（固定为昨天），拿它当扫描时刻会把今天该发的全漏掉。
 * 见 {@link JobInvocation} 关于 bizDate 的说明。
 */
@Component
public class PushSweepDueJob implements JobHandler {

    private final PushService pushes;

    public PushSweepDueJob(PushService pushes) {
        this.pushes = pushes;
    }

    @Override
    public String name() {
        return "push-sweep-due";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = pushes.sweepDue(LocalDateTime.now().toString());
        return n == 0 ? JobResult.skipped("没有到点的推送") : JobResult.success("sent=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration pushSweepDueDeclaration() {
            return JobDeclaration.of("push-sweep-due", "定时推送出队", "0 * * * * *")
                    .ownerModule("user.marketing")
                    .logEveryRun(false)
                    .timeoutSec(50).lockAtMostSec(60)
                    .build();
        }
    }
}
