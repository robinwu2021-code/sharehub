package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobRegistry;
import ai.neargo.sharehub.common.job.JobResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务的**被调用端**（v4/07 的 job target 形状）：声明下发 + 按名触发。
 *
 * <h2>共用调度器接通前，由系统 cron 驱动</h2>
 * v4/08 禁止进程内定时（{@code @Scheduled} 等），理由是定时的东西必须在运营台可见可控；
 * 而共用调度器（v4/14 的 M0 + H1/H4）尚未接通。与预约调价同一套过渡做法
 * （{@code OperationController#tick}）：**系统 cron 天然单实例**，按任务声明的 cron
 * 调本端点。cron 清单 {@code deploy/tencent/cron/powerbank-jobs} 由声明生成，
 * {@code JobCronCoverageTest} 保证「每个声明都有一行、时间与声明一致」——
 * 有 handler 却没人调，是最难发现的一类（代码在那儿摆着，谁看都觉得这事做了）。
 *
 * <p>调度器接通后：它调的就是这两个端点，cron 文件删掉即可，业务代码不动。
 *
 * <h2>只接受本机回环调用</h2>
 * 这些任务会改业务数据（合同到期、线索回收、考核系数、补差…）。
 * 不能因为「nginx 没暴露 /internal」就当它安全 —— 任何能在这台机器上起进程的都够得着应用端口，
 * 依赖反代配置保证鉴权，等于把安全边界放在一个随时可能被改的文件里。
 */
@RestController
public class InternalJobController {

    private static final Logger log = LoggerFactory.getLogger(InternalJobController.class);

    private final JobRegistry registry;

    public InternalJobController(JobRegistry registry) {
        this.registry = registry;
    }

    /** 全部任务声明（调度器接入后由它拉取；现在供运维核对 cron 清单）。 */
    @GetMapping("/internal/job/declarations")
    public List<JobDeclaration> declarations(HttpServletRequest req) {
        requireLoopback(req);
        return registry.declarations();
    }

    /**
     * 触发一个任务。{@code type} 默认 SCHEDULED（cron 调用）；运维手工补跑传 MANUAL，
     * 日志里据此区分「到点跑的」与「人补的」。{@code bizDate} 缺省为昨天（与调度器口径一致）。
     */
    @PostMapping("/internal/job/{name}/trigger")
    public JobResult trigger(@PathVariable String name,
                             @RequestParam(required = false, defaultValue = "SCHEDULED") JobInvocation.Type type,
                             @RequestParam(required = false) LocalDate bizDate,
                             HttpServletRequest req) {
        requireLoopback(req);
        if (!registry.has(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个任务：" + name);
        }
        JobInvocation inv = new JobInvocation(UUID.randomUUID().toString(), type,
                bizDate != null ? bizDate : LocalDate.now().minusDays(1), Map.of());
        JobResult r = registry.trigger(name, inv);
        // 失败在 JobRegistry 里已带 runId 记过 ERROR；这里只记状态变更，便于按 runId 串起一次执行
        log.info("任务已执行 job={} runId={} type={} status={} detail={}",
                name, inv.runId(), type, r.status(), r.detail());
        return r;
    }

    private static void requireLoopback(HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        if (!"127.0.0.1".equals(ip) && !"0:0:0:0:0:0:0:1".equals(ip) && !"::1".equals(ip)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该端点只接受本机调用");
        }
    }
}
