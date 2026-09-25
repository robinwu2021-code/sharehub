package ai.neargo.sharehub.common.job;

/**
 * 一次任务执行的结果。
 *
 * <p><b>本类型是 {@code shop-job-api} 的本地占位</b>，见
 * {@link JobHandler} 的说明与 {@code docs/technical/待办-定时调度接入-执行方案.md} §2。
 *
 * <h2>为什么只有三个状态</h2>
 * v4/07 §二 列了五个：{@code SUCCESS / FAILED / SKIPPED / TIMEOUT / UNREACHABLE}。
 * 后两个是**调度器观察到的**，不是 handler 能返回的 —— 目标进程要是超时或不通，
 * 它根本没机会返回任何东西。把它们放进本枚举会诱导 handler 去「返回 TIMEOUT」，
 * 而那意味着它其实跑完了，与语义相反（v4/07：TIMEOUT 表示「结果未知」）。
 */
public record JobResult(Status status, String detail, String error) {

    public enum Status {
        /** 跑完了，做了该做的事。 */
        SUCCESS,
        /** 跑完了但失败。**业务失败不自动重试**（v4/07 §二），所以 error 要写清谁该做什么。 */
        FAILED,
        /** 没做事，且这是正常的（没有到期的数据 / 前置未完成）。**不是失败**。 */
        SKIPPED
    }

    /** @param detail 做了什么，例如 {@code "handled=12"}。会进 job_log，写给排障的人看。 */
    public static JobResult success(String detail) {
        return new JobResult(Status.SUCCESS, detail, null);
    }

    /** @param error 失败原因。按日志规范：要能回答「谁该做什么」，否则它是噪音。 */
    public static JobResult failed(String error) {
        return new JobResult(Status.FAILED, null, error);
    }

    /** @param detail 为什么跳过，例如 {@code "没有到期的调价单"}。 */
    public static JobResult skipped(String detail) {
        return new JobResult(Status.SKIPPED, detail, null);
    }
}
