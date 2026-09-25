package ai.neargo.sharehub.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把进程里所有 {@link JobHandler} 与 {@link JobDeclaration} 收拢成一张表，并提供手动触发。
 *
 * <p><b>本类是 {@code shop-job-target} 的 {@code JobHandlerRegistry} 的本地占位</b>，
 * 见 {@link JobHandler} 的说明。**它不是调度器**：没有 cron、没有重试、没有留痕、
 * 没有分布式锁 —— 那些等 ai-shop（v4/07 的 J1–J4）。
 *
 * <h2>无条件装配</h2>
 * v4/07 §七 记了 ai-shop 踩过的一个坑：<b>注册表挂在某个 profile 下 → 生产拿不到 →
 * 业务进程起不来</b>。所以本类没有任何 {@code @ConditionalOnProperty}；
 * 将来受开关控制的是**端点**，不是注册表。
 *
 * <h2>对不上就不让启动</h2>
 * 声明与 handler 必须一一对应。少了任何一半都是「看起来配好了、其实不会跑」：
 * <ul>
 *   <li>有声明没 handler → 调度器到点来调，目标说不认识，记一堆 FAILED；</li>
 *   <li>有 handler 没声明 → 调度器<b>根本不知道它存在</b>，于是**它永远不会跑**，
 *       而代码在那儿摆着，谁看都觉得这事做了 —— 这一类最难发现。</li>
 * </ul>
 * 两种都在启动时抛错，而不是记个 WARN 了事：启动失败看得见，WARN 没人看。
 */
@Component
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();
    private final Map<String, JobDeclaration> declarations = new LinkedHashMap<>();

    public JobRegistry(List<JobHandler> handlerBeans, List<JobDeclaration> declarationBeans) {
        for (JobHandler h : handlerBeans) {
            JobHandler dup = handlers.put(h.name(), h);
            if (dup != null) {
                throw new IllegalStateException("任务名重复：" + h.name()
                        + "（" + dup.getClass().getName() + " 与 " + h.getClass().getName()
                        + "）—— 同名会让调度器只调到其中一个，另一个永远不跑");
            }
        }
        for (JobDeclaration d : declarationBeans) {
            JobDeclaration dup = declarations.put(d.name(), d);
            if (dup != null) {
                throw new IllegalStateException("任务声明重复：" + d.name());
            }
        }
        Set<String> onlyDeclared = new TreeSet<>(declarations.keySet());
        onlyDeclared.removeAll(handlers.keySet());
        Set<String> onlyImplemented = new TreeSet<>(handlers.keySet());
        onlyImplemented.removeAll(declarations.keySet());
        if (!onlyDeclared.isEmpty() || !onlyImplemented.isEmpty()) {
            throw new IllegalStateException(
                    "任务声明与实现对不上 —— 有声明没实现：" + onlyDeclared
                            + "；有实现没声明（这些永远不会被调度）：" + onlyImplemented);
        }
        log.info("任务注册表就绪：{} 个 {}", handlers.size(), handlers.keySet());
    }

    /** 全部声明，按注册顺序。调度器接入后由 {@code /internal/job/declarations} 下发这份。 */
    public List<JobDeclaration> declarations() {
        return List.copyOf(declarations.values());
    }

    public boolean has(String name) {
        return handlers.containsKey(name);
    }

    /**
     * 手动 / 进程内触发一个任务。
     *
     * <p><b>异常翻成 {@link JobResult.Status#FAILED}，不外抛</b>：调用方是「触发器」，
     * 它要的是一个结果而不是一个异常栈。异常本身带业务键记进日志（任务名 + runId），
     * 否则排障时只知道「有个任务挂了」，不知道是哪个、哪一次。
     */
    public JobResult trigger(String name, JobInvocation invocation) {
        JobHandler h = handlers.get(name);
        if (h == null) {
            throw new IllegalArgumentException("没有这个任务：" + name + "，已登记的有 " + handlers.keySet());
        }
        try {
            JobResult r = h.run(invocation);
            if (r == null) {
                // 返回 null 等于「跑完了但什么也没说」，当失败处理，别让它冒充成功
                return JobResult.failed("handler 返回了 null job=" + name);
            }
            return r;
        } catch (Exception e) {
            log.error("任务执行失败 job={} runId={}", name, invocation.runId(), e);
            return JobResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 便捷重载：手动触发，入参用 {@link JobInvocation#manual()}。 */
    public JobResult trigger(String name) {
        return trigger(name, JobInvocation.manual());
    }
}
