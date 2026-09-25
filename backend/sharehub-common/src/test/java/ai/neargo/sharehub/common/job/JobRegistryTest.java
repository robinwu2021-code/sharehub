package ai.neargo.sharehub.common.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务注册表：**对不上就别让它启动**，以及**别在这儿长出一个调度器**。
 *
 * <p>本包四个类型是 {@code shop-job-api} 的本地占位（见 {@link JobHandler}），
 * 所以这里守的不是"功能"，而是两件会让人白干的事。
 */
class JobRegistryTest {

    private static JobHandler handler(String name, JobResult result) {
        return new JobHandler() {
            public String name() { return name; }
            public JobResult run(JobInvocation inv) { return result; }
        };
    }

    private static JobDeclaration decl(String name) {
        return JobDeclaration.of(name, "测试任务", "0 0 3 * * *").ownerModule("test").build();
    }

    // ——— 声明与实现必须一一对应 ———

    @Test
    @DisplayName("★ 有实现没声明 → 启动失败（否则它永远不会被调度，而代码摆在那儿像是做了）")
    void handler_without_declaration_fails_startup() {
        assertThatThrownBy(() -> new JobRegistry(List.of(handler("orphan", JobResult.success("x"))), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan")
                .hasMessageContaining("永远不会被调度");
    }

    @Test
    @DisplayName("有声明没实现 → 启动失败（调度器到点来调会记一堆 FAILED）")
    void declaration_without_handler_fails_startup() {
        assertThatThrownBy(() -> new JobRegistry(List.of(), List.of(decl("ghost"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("任务名重复 → 启动失败（同名只会调到一个，另一个永远不跑）")
    void duplicate_name_fails_startup() {
        assertThatThrownBy(() -> new JobRegistry(
                List.of(handler("dup", JobResult.success("a")), handler("dup", JobResult.success("b"))),
                List.of(decl("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务名重复");
    }

    @Test
    void matching_pairs_start_fine() {
        JobRegistry r = new JobRegistry(List.of(handler("ok", JobResult.success("done"))), List.of(decl("ok")));
        assertThat(r.has("ok")).isTrue();
        assertThat(r.declarations()).singleElement().extracting(JobDeclaration::name).isEqualTo("ok");
    }

    // ——— 触发 ———

    @Test
    void trigger_returns_the_handler_result() {
        JobRegistry r = new JobRegistry(List.of(handler("ok", JobResult.success("handled=3"))), List.of(decl("ok")));
        assertThat(r.trigger("ok").status()).isEqualTo(JobResult.Status.SUCCESS);
        assertThat(r.trigger("ok").detail()).isEqualTo("handled=3");
    }

    @Test
    @DisplayName("★ handler 抛异常 → FAILED，不外抛（调用方要的是结果，不是异常栈）")
    void exception_becomes_failed() {
        JobHandler boom = new JobHandler() {
            public String name() { return "boom"; }
            public JobResult run(JobInvocation inv) { throw new IllegalStateException("数据库挂了"); }
        };
        JobResult r = new JobRegistry(List.of(boom), List.of(decl("boom"))).trigger("boom");
        assertThat(r.status()).isEqualTo(JobResult.Status.FAILED);
        assertThat(r.error()).contains("数据库挂了");
    }

    @Test
    @DisplayName("★ handler 返回 null → FAILED，不许冒充成功")
    void null_result_becomes_failed() {
        JobResult r = new JobRegistry(List.of(handler("nil", null)), List.of(decl("nil"))).trigger("nil");
        assertThat(r.status()).isEqualTo(JobResult.Status.FAILED);
    }

    @Test
    void unknown_job_is_refused_with_the_list_of_known_ones() {
        JobRegistry r = new JobRegistry(List.of(handler("ok", JobResult.success("x"))), List.of(decl("ok")));
        assertThatThrownBy(() -> r.trigger("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ok");   // 报出已登记的，省得调用方去翻代码
    }

    @Test
    @DisplayName("手动触发的 bizDate 取昨天——与调度器口径一致，否则手动跑和定时跑算的不是同一天")
    void manual_invocation_uses_yesterday() {
        assertThat(JobInvocation.manual().bizDate())
                .isEqualTo(java.time.LocalDate.now().minusDays(1));
        assertThat(JobInvocation.manual().type()).isEqualTo(JobInvocation.Type.MANUAL);
        assertThat(JobInvocation.manual().params()).isEmpty();
    }

    // ——— 声明的自检 ———

    @Test
    @DisplayName("★ 锁比超时短 → 拒绝（锁先过期会让另一个副本并发执行，而两边都显示成功）")
    void lock_shorter_than_timeout_is_refused() {
        assertThatThrownBy(() -> JobDeclaration.of("t", "标题", "0 0 3 * * *")
                .timeoutSec(60).lockAtMostSec(30).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("并发执行");
    }

    @Test
    @DisplayName("锁比超时长太多 → 拒绝（进程被 kill 后任务会长时间一次都不跑）")
    void lock_far_longer_than_timeout_is_refused() {
        assertThatThrownBy(() -> JobDeclaration.of("t", "标题", "0 0 3 * * *")
                .timeoutSec(10).lockAtMostSec(41).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一次都不跑");
    }

    @Test
    void title_is_required_because_the_ops_page_shows_it() {
        assertThatThrownBy(() -> JobDeclaration.of("t", " ", "0 0 3 * * *").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
    }

    // ——— 别在这儿长出一个调度器 ———

    @Test
    @DisplayName("★★ 本包不许出现调度能力——它是占位，不是调度器")
    void this_package_must_not_grow_a_scheduler() {
        /*
         * v4/07 定的是「不自建调度器，用 ai-shop 的任务服务」。本包只是那套 API 的本地占位，
         * 让被阻塞的业务任务先写得出来。**最可能的走偏是有人顺手在这儿加个定时**
         * ——加完它就能跑了，于是没人再去接 ai-shop，最后落成一套要废弃的第二实现，
         * 而且它会比真调度器先被信任。所以把这条写成卡口，而不是只写在注释里。
         *
         * cron 解析 / 失败重试 / job_run 留痕 / 运营端页面 一律等 ai-shop（J1–J4）。
         */
        Path pkg = Path.of("src/main/java/ai/neargo/sharehub/common/job");
        assertThat(pkg).as("包路径变了就改这里").isDirectory();

        String[] forbidden = {
                "@Scheduled", "@EnableScheduling", "ScheduledExecutorService",
                "CronExpression", "CronTrigger", "TaskScheduler", "ShedLock",
        };
        try (Stream<Path> files = Files.walk(pkg)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        String src = read(p);
                        return Stream.of(forbidden)
                                .filter(kw -> src.contains(kw))
                                .map(kw -> p.getFileName() + " 出现了 " + kw);
                    })
                    .toList();
            assertThat(offenders).as("""
                    本包是 shop-job-api 的占位，不是调度器。要调度能力就去接 ai-shop
                    （v4/07 的 J1–J4），别在这里长出第二套。""").isEmpty();
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + pkg, e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + p, e);
        }
    }
}
