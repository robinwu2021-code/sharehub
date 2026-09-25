package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobRegistry;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统 cron 清单 {@code deploy/tencent/cron/powerbank-jobs} **与任务声明逐字一致**。
 *
 * <h2>为什么要这一条</h2>
 * 共用调度器接通前，任务由系统 cron 调 {@code /internal/job/{name}/trigger}（见 {@code InternalJobController}）。
 * 新加一个任务而忘了往 cron 里加一行，它就**永远不会跑** —— 代码摆在那儿，谁看都觉得这事做了；
 * 改了声明里的时间而 cron 没跟，跑的时间就和声明说的不一样。两种都不报错。
 *
 * <h2>红了怎么办</h2>
 * 失败信息里有完整的期望内容，**整份覆盖**那个文件即可（它就是由本测试的规则生成的，别手改）。
 */
class JobCronCoverageTest extends ApiTestSupport {

    private static final Path CRON_FILE = Path.of("..", "..", "deploy", "tencent", "cron", "powerbank-jobs");

    /** 已由别的方式驱动、不进本清单的任务 → 由谁驱动。 */
    private static final Map<String, String> DRIVEN_ELSEWHERE = Map.of(
            "outbox-dispatch", "OutboxDispatchScheduler（进程内，登记在 known-inprocess-schedules.txt；秒级间隔 cron 表达不了）",
            "price-adjust-apply", "deploy/tencent/cron/powerbank-price-adjustment（每分钟调 /internal/trade/price-adjustments/tick）");

    @Autowired
    private JobRegistry registry;

    @Test
    @DisplayName("★ 每个任务声明在 cron 清单里都有一行，时间与声明一致（少一行 = 那个任务永远不跑）")
    void cron_file_matches_declarations() throws Exception {
        String expected = render(registry.declarations());
        String actual = Files.exists(CRON_FILE) ? Files.readString(CRON_FILE) : "(文件不存在)";
        assertThat(actual)
                .as("cron 清单与任务声明不一致 —— 用下面的期望内容整份覆盖 %s：\n%s", CRON_FILE, expected)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("内部触发端点：本机可调、未知任务 404")
    void trigger_endpoint_works_from_loopback() {
        Resp ok = post("/internal/job/file-temp-purge/trigger?type=MANUAL", null, null);
        assertThat(ok.status).isEqualTo(200);
        assertThat(ok.body.path("data").path("status").asText()).isIn("SUCCESS", "SKIPPED");

        Resp missing = post("/internal/job/no-such-job/trigger", null, null);
        assertThat(missing.status).isEqualTo(404);

        Resp decl = get("/internal/job/declarations", null);
        assertThat(decl.status).isEqualTo(200);
        assertThat(decl.body.path("data").size()).isEqualTo(registry.declarations().size());
    }

    static String render(List<JobDeclaration> decls) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # powerbank · 业务定时任务（由 JobCronCoverageTest 按任务声明生成，请勿手改 —— 红了就按失败信息整份覆盖）
                #
                # 为什么是系统 cron：v4/08 禁止进程内定时（定时的东西必须在运营台可见可控），
                # 而共用调度器（v4/14 的 M0 + H1/H4）尚未接通。系统 cron 天然单实例，是单体阶段更小的代价；
                # 与 powerbank-price-adjustment 同一套做法。调度器接通后本文件删除，它调的是同一个端点。
                #
                # 每行：flock -n 防同一任务重叠执行（上一轮没跑完，这一轮直接跳过）；curl 超时 = 声明的 lockAtMostSec。
                # 时间按本机时区解释，与应用 JVM 时区一致（同一台机器）；任务内部的营业日口径按 Asia/Dubai 自行换算。
                # /internal/job/** 只接受回环地址（InternalJobController 自判），这里不带鉴权。
                #
                # 安装：sudo cp deploy/tencent/cron/powerbank-jobs /etc/cron.d/
                #       sudo chown root:root /etc/cron.d/powerbank-jobs && sudo chmod 644 /etc/cron.d/powerbank-jobs
                # 手工补跑：curl -s -X POST 'http://127.0.0.1:8082/internal/job/<任务名>/trigger?type=MANUAL'
                # 核对清单：curl -s http://127.0.0.1:8082/internal/job/declarations
                SHELL=/bin/bash
                PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin

                """);
        List<String> skipped = new ArrayList<>();
        for (JobDeclaration d : decls) {
            if (DRIVEN_ELSEWHERE.containsKey(d.name())) {
                skipped.add("# " + d.name() + " —— 不在本清单：" + DRIVEN_ELSEWHERE.get(d.name()));
                continue;
            }
            String[] f = d.defaultCron().trim().split("\\s+");
            if (f.length != 6 || !"0".equals(f[0])) {
                throw new IllegalStateException("任务 " + d.name() + " 的 cron「" + d.defaultCron()
                        + "」秒位不是 0，系统 cron 表达不了 —— 改声明，或登记进 DRIVEN_ELSEWHERE 说明由谁驱动");
            }
            sb.append("# ").append(d.name()).append(" · ").append(d.title())
                    .append("（").append(d.ownerModule()).append("）· 声明 cron: ").append(d.defaultCron()).append('\n');
            sb.append(String.join(" ", f[1], f[2], f[3], f[4], f[5]))
                    .append(" deploy flock -n /run/lock/powerbank-job-").append(d.name()).append(".lock")
                    .append(" curl -s -m ").append(d.lockAtMostSec())
                    .append(" -X POST 'http://127.0.0.1:8082/internal/job/").append(d.name()).append("/trigger' -o /dev/null\n\n");
        }
        for (String s : skipped) sb.append(s).append('\n');
        return sb.toString();
    }
}
