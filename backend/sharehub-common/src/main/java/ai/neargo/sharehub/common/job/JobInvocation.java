package ai.neargo.sharehub.common.job;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 一次任务执行的入参（v4/07 §二：{@code JobInvocation(runId, type, bizDate, params)}）。
 *
 * <p><b>本类型是 {@code shop-job-api} 的本地占位</b>，见 {@link JobHandler}。
 *
 * @param runId   本次执行的唯一标识。**handler 要把它写进自己的日志** ——
 *                没有它，一条业务日志对不上调度器那边的哪一次执行。
 * @param type    谁触发的。handler 一般不该按它分支：手动触发与定时触发**应当做同一件事**，
 *                否则「手动跑一遍看看」就验不了定时的行为。
 * @param bizDate 业务日期，调度器固定传「昨天」（v4/07 §二）。
 *                ⚠️ 它按**调度器 JVM 时区**算；跨时区的业务口径（日结、账期）要在
 *                handler 内部按 {@code Asia/Dubai} 换算，别直接拿它当营业日。
 * @param params  运营端手动触发时可带的参数。**可能为空 Map，不会为 null**。
 */
public record JobInvocation(String runId, Type type, LocalDate bizDate, Map<String, String> params) {

    public enum Type {
        /** 调度器按 cron 触发。 */
        SCHEDULED,
        /** 人在运营端点的，或本机进程内触发。 */
        MANUAL
    }

    public JobInvocation {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * 进程内 / 手动触发用。{@code bizDate} 取昨天，与调度器口径一致 ——
     * 取今天的话，手动跑一遍和定时跑一遍算的是不同的日子，对不上账。
     */
    public static JobInvocation manual() {
        return new JobInvocation(UUID.randomUUID().toString(), Type.MANUAL,
                LocalDate.now().minusDays(1), Map.of());
    }
}
