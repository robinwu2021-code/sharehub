package ai.neargo.sharehub.wo.ext.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.InspectionPlan;
import ai.neargo.sharehub.wo.ext.entity.WoInspectionPlan;

/** 巡检计划（配置类 → 通用 CRUD）。停用 = {@code active=0}，全站零 DELETE。 */
public interface InspectionPlanService extends CrudService<WoInspectionPlan, InspectionPlan> {

    /**
     * 立即执行一次巡检计划：按计划生成巡检工单。
     *
     * <p><b>不是定时任务</b> —— 这是运营手动触发的「现在就跑一次」。
     * 定时调度需分布式锁（多副本会并发跑），与 outbox 投递器一起设计。
     */
    Object run(String planNo);

    /**
     * 把**所有到期的**启用计划跑一遍。定时任务 {@code inspection-plan-run} 调这个。
     *
     * <h3>为什么不需要「下次执行时刻」这类调度状态</h3>
     * {@link #run(String)} 自带周期幂等键（{@code lastRunPeriod} = 按 frequency 算的周期键，
     * 同周期第二次整批拒）。所以本方法只要**遍历启用的计划逐个调**即可：
     * DAILY 的每天都会真跑，WEEKLY/MONTHLY 的在本周期跑过之后自然被拒。
     * 这样不必维护 {@code nextAt}（那一列注释写的是「由调度器回写」，而今天没人写它，
     * 拿它当扫描条件会**永远扫不出东西**）。
     *
     * <h3>一个计划失败不影响其余</h3>
     * 逐个计划独立成败：某条路线的站点没在册机柜时 {@code run} 会抛，
     * 但**其余计划照跑** —— 否则一条坏路线能让全站巡检停摆，而界面上只看到"任务失败"。
     *
     * @return 本次真正生成了工单的计划号（本周期已跑过的不计入）
     */
    java.util.List<String> runDue();
}
