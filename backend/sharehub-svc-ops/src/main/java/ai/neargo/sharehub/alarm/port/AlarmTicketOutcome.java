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
import ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand;
import ai.neargo.sharehub.api.platform.flow.TicketOutcomeHandler;
import ai.neargo.sharehub.common.event.DomainEventBus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 告警工单的 OpsKit 回写（方案 23 §十）：单号回填 · 命令执行时查单号 · <b>完工复核</b>。
 *
 * <p>完工复核与本地的 {@code WorkOrderReviewListener} 同一套判定：OpsKit 工单以「解决」结单（{@code ticket.closed} + DONE）→
 * 复核挂在这张单上的全部告警 —— 全部恢复 → 告警以 RESOLVED 关闭；有未恢复的 → 在单上留内部评论说明哪条没过，告警保持打开，
 * 运营在告警页看得到它还开着。按单号找告警而不是按事件里的业务对象：并单后一张单挂着多条告警，事件只带首张的告警号。
 */
@Component
public class AlarmTicketOutcome implements TicketOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(AlarmTicketOutcome.class);
    static final String SYSTEM = "SYSTEM";

    private final DevAlarmMapper alarms;
    private final DevAlarmCodeMapper codes;
    private final AlarmEngine engine;
    private final DomainEventBus events;

    public AlarmTicketOutcome(DevAlarmMapper alarms, DevAlarmCodeMapper codes, AlarmEngine engine, DomainEventBus events) {
        this.alarms = alarms;
        this.codes = codes;
        this.engine = engine;
        this.events = events;
    }

    @Override
    public String bizType() {
        return OpsKitAlarmTicketPort.BIZ_TYPE;
    }

    @Override
    @Transactional
    public void onTicketCreated(String alarmNo, String ticketNo, boolean attached) {
        DataScopeContext.executeWithoutScope(() -> alarms.update(null, new LambdaUpdateWrapper<DevAlarm>()
                .eq(DevAlarm::getAlarmNo, alarmNo).isNull(DevAlarm::getDispositionRef)
                .set(DevAlarm::getDispositionRef, ticketNo).set(DevAlarm::getWoNo, ticketNo)));
        if (attached) log.info("告警并入 OpsKit 既有未结工单 alarmNo={} → {}", alarmNo, ticketNo);
    }

    @Override
    public void onTicketFailed(String alarmNo, String error) {
        // 谁该做什么：运营在告警页对这条告警点「转工单」即重送（同一幂等键，OpsKit 恢复后即可建上）
        log.error("告警送 OpsKit 开工单失败（已转死信）alarmNo={}：{} —— 运营可在告警页重新「转工单」", alarmNo, error);
    }

    @Override
    public String ticketNoOf(String alarmNo) {
        DevAlarm a = DataScopeContext.executeWithoutScope(() -> alarms.selectOne(new LambdaQueryWrapper<DevAlarm>()
                .eq(DevAlarm::getAlarmNo, alarmNo).last("LIMIT 1")));
        return a == null ? null : a.getDispositionRef();
    }

    @Override
    @Transactional
    public void onTicketEvent(String eventType, String alarmNo, String ticketNo, String statusCategory, String resolution) {
        // 只在「以解决结单」时复核：作废（CANCELLED）的单不代表修好了；transitioned 与 closed 同发，只认 closed 一次
        if (!"ticket.closed".equals(eventType) || !"DONE".equals(statusCategory) || ticketNo == null) return;
        DataScopeContext.executeWithoutScope(() -> {
            List<DevAlarm> linked = alarms.selectList(new LambdaQueryWrapper<DevAlarm>()
                    .eq(DevAlarm::getDispositionType, AlarmDisposition.WORK_ORDER.name()).eq(DevAlarm::getDispositionRef, ticketNo));
            if (linked.isEmpty()) return null;
            List<String> failed = linked.stream().filter(a -> !passed(a)).map(a -> a.getAlarmNo() + "（" + a.getAlarmCode() + "）").toList();
            if (failed.isEmpty()) {
                for (DevAlarm a : linked) {
                    if (AlarmStatus.CLOSED.name().equals(a.getStatus())) continue;
                    engine.close(a, AlarmCloseReason.RESOLVED, "工单 " + ticketNo + " 修复并复核通过", SYSTEM);
                }
            } else {
                // 谁该做什么：运维回现场再看；告警仍开着，告警页可见
                log.warn("OpsKit 工单已解决但告警复核未通过 ticketNo={} 未恢复={}", ticketNo, failed);
                events.publish(new OpsKitTicketCommand(bizType(), alarmNo, ticketNo, OpsKitTicketCommand.COMMENT,
                        Map.of("body", "完工复核未通过：以下告警条件仍成立，告警保持打开 —— " + String.join("、", failed))));
            }
            return null;
        });
    }

    /** 与 WorkOrderReviewListener#reviewOne 同一判定（已关 / 已恢复防抖中 / 处置完成即关类 → 通过；否则重跑判定） */
    private boolean passed(DevAlarm a) {
        if (AlarmStatus.CLOSED.name().equals(a.getStatus()) || a.getRecoveredAt() != null) return true;
        DevAlarmCode code = codes.selectOne(new LambdaQueryWrapper<DevAlarmCode>().eq(DevAlarmCode::getCode, a.getAlarmCode()).last("limit 1"));
        if (code != null && RecoverRule.DISPOSITION_DONE.name().equals(code.getRecoverRule())) return true;
        return !engine.stillHolds(a);
    }
}
