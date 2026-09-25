package ai.neargo.sharehub.common.job;

/**
 * 一个定时任务要做的事。实现类注册成 Spring bean 即可被 {@link JobRegistry} 收走。
 *
 * <h2>⚠️ 本包四个类型是 {@code shop-job-api} 的本地占位，将来要被替换</h2>
 * v4/07 定的是「不自建，用 ai-shop 的任务服务」，但那条链自 2026-09-24 起卡着：
 * `backend/pom.xml` 的 enforcer 禁止依赖 {@code ai.neargo.shop:*}，而放行名单里的
 * {@code shop-job-api} / {@code shop-job-target} <b>两个坐标都不存在</b> ——
 * 于是本仓连 {@code JobHandler} 都写不了，v4/07 的 J0（先写好首批任务）无从开始。
 *
 * <p>所以本包先按 v4/07 §4.1 的签名自己定义一份零依赖的。**业务侧只 import 本包的四个类型**，
 * 不要依赖 {@link JobRegistry} 的实现细节；ai-shop 构件就绪后，替换是纯 import 改名，
 * 差异只会落在本包这几个文件上。
 *
 * <p><b>本包不进 {@code known-inprocess-schedules.txt}</b>：那份台账记的是会自己 tick 的东西，
 * 而注册表只在被调用时跑，混进去会让扫台账的人得到假阳性。占位身份由本注释
 * 与 {@code JobRegistryTest.this_package_must_not_grow_a_scheduler} 守着。
 *
 * <h2>这里不会长出一个调度器</h2>
 * 本包只做「注册表 + 手动/进程内触发」。**cron 解析、失败重试、job_run 留痕、
 * 运营端页面一律等 ai-shop** —— 那些正是 v4/07 选择不自建的理由，
 * 自己写一份等于制造一套要废弃的东西，而且它会比真调度器先被信任。
 *
 * <h2>写 handler 的三条</h2>
 * <ol>
 *   <li><b>幂等是你的责任，不是调度器的</b>（v4/07 §二）。台账期没有分布式锁，
 *       多副本会重复执行；按业务键 + 状态条件更新，别靠「不会重复」；</li>
 *   <li><b>别在 handler 里放业务逻辑</b>。handler 是一层壳：调 service 的一个方法、
 *       把结果翻成 {@link JobResult}。业务逻辑留在 service 里才能被别的入口复用与单测；</li>
 *   <li><b>不要自己捕获异常后返回 SUCCESS</b>。抛出去，{@link JobRegistry} 会翻成
 *       {@link JobResult.Status#FAILED} 并带业务键记日志 —— 吞掉的话任务天天"成功"，
 *       而它什么都没做，这正是本仓库反复修的那一类缺陷。</li>
 * </ol>
 *
 * @see JobDeclaration 每个 handler 必须配一个同名声明，否则启动就失败
 */
public interface JobHandler {

    /** 任务名，同一调度器内唯一（v4/07 §五 命名规则：网关的加 {@code gw-} 前缀）。 */
    String name();

    /** 干活。允许抛异常 —— 见类注释第 3 条。 */
    JobResult run(JobInvocation invocation);
}
