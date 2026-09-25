package ai.neargo.sharehub.common.job;

/**
 * 一个任务的登记信息：调度器靠它知道这个任务叫什么、多久跑一次、跑多久算超时。
 *
 * <p><b>本类型是 {@code shop-job-api} 的本地占位</b>，见 {@link JobHandler}。
 *
 * <h2>为什么声明与 handler 分开</h2>
 * handler 是**怎么做**，声明是**怎么调度** —— 后者是运营要改的（改 cron、停用、手动触发），
 * 改它不该碰业务代码。调度器接入后，声明由 {@code GET /internal/job/declarations} 下发。
 *
 * <h2>新任务默认停用</h2>
 * v4/07 §二：{@code start-disabled = true}，首次登记一律停用，运营核对后逐个开启。
 * 本地占位期还没有「启用状态」这个概念（没有 job_definition 表），
 * 所以本类不带 enabled —— <b>别加</b>，加了就是在本仓库复制一份调度器的状态机。
 */
public record JobDeclaration(
        String name,
        String title,
        String ownerModule,
        String defaultCron,
        int timeoutSec,
        int lockAtMostSec,
        boolean manualTrigger,
        boolean logEveryRun) {

    public JobDeclaration {
        require(name != null && !name.isBlank(), "任务名必填");
        require(title != null && !title.isBlank(), "任务标题必填：任务名给机器看，标题给运营看，缺了运营端只剩一串英文");
        require(defaultCron != null && !defaultCron.isBlank(), "默认 cron 必填 name=" + name);
        require(timeoutSec > 0, "超时必须为正 name=" + name);
        /*
         * timeout ≤ lock ≤ 4 × timeout（v4/07 §4.1，声明构造时校验）。
         *
         * 下界：锁比超时短 —— 任务还在跑锁就没了，另一个副本进来并发执行，
         *       而它看起来一切正常（两边都会 SUCCESS）。
         * 上界：锁比超时长太多 —— 进程被 kill 后锁要等很久才释放，
         *       这段时间里任务**一次都不会跑**，而调度器只记 SKIPPED，没人觉得不对。
         */
        require(lockAtMostSec >= timeoutSec,
                "锁定时长不能短于超时：锁先过期会让另一个副本并发执行，而两边都显示成功 name=" + name);
        require(lockAtMostSec <= 4 * timeoutSec,
                "锁定时长不能超过超时的 4 倍：进程被 kill 后锁迟迟不释放，任务会长时间一次都不跑 name=" + name);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    /** 常用形态：每天一次。{@code cron} 为 6 段 Spring 写法（到秒）。 */
    public static Builder daily(String name, String title, String cron) {
        return new Builder(name, title, cron);
    }

    public static Builder of(String name, String title, String cron) {
        return new Builder(name, title, cron);
    }

    public static final class Builder {
        private final String name;
        private final String title;
        private final String cron;
        private String ownerModule = "";
        private int timeoutSec = 50;
        private int lockAtMostSec = 60;
        private boolean manualTrigger = true;
        private boolean logEveryRun = false;

        private Builder(String name, String title, String cron) {
            this.name = name;
            this.title = title;
            this.cron = cron;
        }

        /** 归属模块，例如 {@code trade.order}。运营端按它分组。 */
        public Builder ownerModule(String v) { this.ownerModule = v; return this; }

        /** 超时秒数。v4/07 §七：**按实测 P99 的 3 倍设** —— 设太短会一直记 TIMEOUT 而没人告警。 */
        public Builder timeoutSec(int v) { this.timeoutSec = v; return this; }

        public Builder lockAtMostSec(int v) { this.lockAtMostSec = v; return this; }

        /** 允许运营在页面上手动触发。默认允许。 */
        public Builder manualTrigger(boolean v) { this.manualTrigger = v; return this; }

        /** 每次都记一条日志（高频任务建议 false，否则日志被它淹掉）。 */
        public Builder logEveryRun(boolean v) { this.logEveryRun = v; return this; }

        public JobDeclaration build() {
            return new JobDeclaration(name, title, ownerModule, cron,
                    timeoutSec, lockAtMostSec, manualTrigger, logEveryRun);
        }
    }
}
