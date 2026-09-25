package ai.neargo.sharehub.alarm.engine;

import ai.neargo.sharehub.alarm.AlarmCloseReason;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.DispositionPreview;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.TickResult;
import ai.neargo.sharehub.alarm.entity.DevAlarm;

import java.time.LocalDateTime;

/**
 * 业务告警引擎（TDD/05 §五）：判定器只回答「现在是否成立」，开 / 累加 / 恢复 / 关闭 / 处置全在这里。
 *
 * <p>两条铁律：判定器不写库；同一对象同一码任一时刻最多一条未关闭告警（{@code uk_alarm_open} 保证）。
 */
public interface AlarmEngine {

    /** 每分钟：STATE 判定 → 到期处置 → 恢复关闭。{@code now} 可注入（测试推进时间）。 */
    TickResult tick(LocalDateTime now);

    /**
     * 只对给定站点做一轮判定、处置与恢复（运营「立即重算该站点」，也用于测试隔离）。
     * 订单类全局判定只取落在这些站点上的异常。
     */
    TickResult tickSites(LocalDateTime now, java.util.Collection<String> siteNos);

    /** EVENT 判定（设备信号等，由监听器转进来）。 */
    void onEvent(Object event, LocalDateTime now);

    /** 到期未处置的告警逐条处置（开单延迟到了）。返回本次处置条数。 */
    int dispatchDue(LocalDateTime now);

    /** 所有关闭都走这里：释放本告警持有的保护动作 + 联动处置单。只关未关闭的（幂等）。 */
    boolean close(DevAlarm alarm, AlarmCloseReason reason, String note, String operator);

    /** 人工关闭：RESOLVED / FALSE_ALARM / SELF_HEALED；安全类与「处置完成才关」的码拒绝人工 RESOLVED。 */
    void closeManually(String alarmNo, String reason, String note);

    /** 人工立即处置（忽略开单延迟）。返回处置单号（工单号 / 待办号 / 客服单号），NOTIFY 返回 null。 */
    String disposeNow(String alarmNo, LocalDateTime now);

    DispositionPreview preview(String alarmNo);

    /** 对该告警的对象立即重跑判定：仍产出同一 dedupKey → 仍成立（完工复核、待办完成用）。事件型码返回 false。 */
    boolean stillHolds(DevAlarm alarm);

    /** 处置单完结回调（待办完成等）：条件已不成立 → 关闭；仍成立 → 清空处置，下一轮重新处置。 */
    void onDispositionDone(String dispositionType, String ref);
}
