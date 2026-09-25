package ai.neargo.sharehub.alarm.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.alarm.AlarmCloseReason;
import ai.neargo.sharehub.alarm.AlarmDisposition;
import ai.neargo.sharehub.alarm.AlarmStatus;
import ai.neargo.sharehub.alarm.RecoverRule;
import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmMapper;
import ai.neargo.sharehub.api.ops.event.WorkOrderCompletedEvent;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.ReviewItem;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 完工复核（TDD/05 §7.5，联动 E12）：工单进入 DONE → 关联告警都已恢复 → 自动验收；否则保持 DONE 等人工。
 * wo 不认识 alarm，所以由 alarm 订阅 wo 的事件。人工单（无关联告警）不复核。
 */
@Component
public class WorkOrderReviewListener {

    private final DevAlarmMapper alarms;
    private final DevAlarmCodeMapper codes;
    private final AlarmEngine engine;
    private final WoOpsService woOps;

    public WorkOrderReviewListener(DevAlarmMapper alarms, DevAlarmCodeMapper codes, AlarmEngine engine,
                                   WoOpsService woOps) {
        this.alarms = alarms;
        this.codes = codes;
        this.engine = engine;
        this.woOps = woOps;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(WorkOrderCompletedEvent e) {
        DataScopeContext.executeWithoutScope(() -> {
            List<DevAlarm> linked = alarms.selectList(new LambdaQueryWrapper<DevAlarm>()
                    .eq(DevAlarm::getDispositionType, AlarmDisposition.WORK_ORDER.name()).eq(DevAlarm::getDispositionRef, e.woNo()));
            if (linked.isEmpty()) return null;
            List<ReviewItem> items = linked.stream().map(this::reviewOne).toList();
            boolean allOk = items.stream().allMatch(ReviewItem::passed);
            woOps.recordReview(e.woNo(), allOk, items);
            if (allOk) {
                for (DevAlarm a : linked) {
                    if (AlarmStatus.CLOSED.name().equals(a.getStatus())) continue;
                    engine.close(a, AlarmCloseReason.RESOLVED, "工单 " + e.woNo() + " 修复并复核通过", "SYSTEM");
                }
            }
            return null;
        });
    }

    private ReviewItem reviewOne(DevAlarm a) {
        if (AlarmStatus.CLOSED.name().equals(a.getStatus())) return new ReviewItem(a.getAlarmNo(), a.getAlarmCode(), true, "已关闭");
        if (a.getRecoveredAt() != null) return new ReviewItem(a.getAlarmNo(), a.getAlarmCode(), true, "已恢复（防抖中）");
        DevAlarmCode code = codes.selectOne(new LambdaQueryWrapper<DevAlarmCode>().eq(DevAlarmCode::getCode, a.getAlarmCode()).last("limit 1"));
        if (code != null && RecoverRule.DISPOSITION_DONE.name().equals(code.getRecoverRule())) {
            return new ReviewItem(a.getAlarmNo(), a.getAlarmCode(), true, "处置完成即关闭类（由验收人现场确认）");
        }
        boolean holds = engine.stillHolds(a);
        return new ReviewItem(a.getAlarmNo(), a.getAlarmCode(), !holds, holds ? "重跑判定：条件仍成立" : "重跑判定：已恢复");
    }
}
