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
}
