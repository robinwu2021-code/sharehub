package ai.neargo.sharehub.alarm.port;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand;
import ai.neargo.sharehub.api.platform.flow.TicketRequestedEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处置单 = OpsKit「告警工单」（方案 23 §十）。全部走 outbox：开单发 {@link TicketRequestedEvent}，
 * 其余联动发 {@link OpsKitTicketCommand}，与告警写入同事务，OpsKit 暂时不可用不拖住告警判定。
 *
 * <p>并单沿用告警引擎的并单键作 OpsKit {@code dedupeKey}：同键的告警落到同一张单，回填同一个单号。
 *
 * <p><b>切换期</b>：开关打开前开出的处置单是本地工单（号以 {@code WO} 开头）。它们的后续联动（升级、并单、恢复）
 * 仍交本地实现 —— 拿本地单号去找 OpsKit 只会一路失败到死信，而本地那张单反倒没人动。新开的单一律走 OpsKit。
 */
@Component
@ConditionalOnProperty(name = "sharehub.flow.alarm-ticket.engine", havingValue = "OPSKIT")
public class OpsKitAlarmTicketPort implements AlarmTicketPort {

    static final String BIZ_TYPE = "alarm";
    static final String TYPE_CODE = "alarm";

    private final DomainEventBus events;
    /** 切换期兜底：本地工单号仍按本地处理 */
    private final LocalAlarmTicketPort local;

    public OpsKitAlarmTicketPort(DomainEventBus events, ai.neargo.sharehub.wo.ext.service.WoOpsService woOps) {
        this.events = events;
        this.local = new LocalAlarmTicketPort(woOps);
    }

    /** 开关打开前的本地工单（wo_order 的号一律 WO 开头；OpsKit 告警工单前缀 AL） */
    static boolean isLocal(String ref) {
        return ref != null && ref.startsWith("WO");
    }

    /** 告警四档 → 「告警工单」类型的优先级代号（P1 最急） */
    static String priorityOf(String woPriority) {
        return switch (woPriority == null ? "MEDIUM" : woPriority) {
            case "URGENT" -> "P1";
            case "HIGH" -> "P2";
            case "LOW" -> "P4";
            default -> "P3";
        };
    }

    @Override
    public boolean viaOpsKit() {
        return true;
    }

    @Override
    public String open(AlarmDraft d, DevAlarm a) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("alarmCode", a.getAlarmCode());
        if (d.siteNo() != null) fields.put("siteNo", d.siteNo());
        if (d.cabinetNo() != null) fields.put("cabinetNo", d.cabinetNo());
        String where = d.cabinetNo() != null ? "机柜 " + d.cabinetNo() : d.siteNo() != null ? "站点 " + d.siteNo() : "";
        String title = (a.getAlarmCode() + " " + where).trim();
        events.publish(new TicketRequestedEvent(BIZ_TYPE, a.getAlarmNo(), TYPE_CODE,
                title.length() > 80 ? title.substring(0, 79) + "…" : title, d.description(), priorityOf(d.priority()),
                fields, "告警 " + a.getAlarmNo() + (where.isEmpty() ? "" : " · " + where), List.of(), d.mergeKey(), null, null));
        return null;
    }

    @Override
    public boolean isFinished(String ref) {
        return isLocal(ref) && local.isFinished(ref);
    }

    @Override
    public void raisePriority(String ref, DevAlarm alarm, String priority, String reason) {
        if (isLocal(ref)) { local.raisePriority(ref, alarm, priority, reason); return; }
        command(ref, alarm, OpsKitTicketCommand.RAISE_PRIORITY, Map.of("priority", priorityOf(priority), "note", reason));
    }

    @Override
    public void merge(String ref, DevAlarm lower, String intoRef, DevAlarm upper, String reason) {
        // 两张都是本地单才能本地并；一本地一 OpsKit 并不到一起 —— 留说明，各自走完
        if (isLocal(ref) && isLocal(intoRef)) { local.merge(ref, lower, intoRef, upper, reason); return; }
        if (isLocal(ref)) { local.annotate(ref, lower, reason + "（上层为 OpsKit 工单 " + (intoRef == null ? "开单中" : intoRef) + "，未并单）"); return; }
        Map<String, Object> args = new LinkedHashMap<>();
        if (intoRef != null) args.put("intoTicketNo", intoRef); else args.put("intoBizNo", upper.getAlarmNo());
        args.put("reason", reason);
        command(ref, lower, OpsKitTicketCommand.MERGE, args);
    }

    @Override
    public void annotate(String ref, DevAlarm alarm, String note) {
        if (isLocal(ref)) { local.annotate(ref, alarm, note); return; }
        command(ref, alarm, OpsKitTicketCommand.COMMENT, Map.of("body", note));
    }

    @Override
    public void recovered(String ref, DevAlarm alarm, String note) {
        if (isLocal(ref)) { local.recovered(ref, alarm, note); return; }
        command(ref, alarm, OpsKitTicketCommand.ALARM_RECOVERED, Map.of("note", note));
    }

    private void command(String ref, DevAlarm alarm, String command, Map<String, Object> args) {
        events.publish(new OpsKitTicketCommand(BIZ_TYPE, alarm.getAlarmNo(), ref, command, args));
    }
}
