package ai.neargo.sharehub.alarm.port;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.wo.WorkOrderStatus;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 处置单 = 本地工单（原样搬自 AlarmEngineImpl，行为不变）。开关缺省即此实现 */
@Component
@ConditionalOnProperty(name = "sharehub.flow.alarm-ticket.engine", havingValue = "LOCAL", matchIfMissing = true)
public class LocalAlarmTicketPort implements AlarmTicketPort {

    private final WoOpsService woOps;

    public LocalAlarmTicketPort(WoOpsService woOps) {
        this.woOps = woOps;
    }

    @Override
    public boolean viaOpsKit() {
        return false;
    }

    @Override
    public String open(AlarmDraft draft, DevAlarm alarm) {
        return woOps.openOrAttach(draft);
    }

    @Override
    public boolean isFinished(String ref) {
        String status = woOps.statusOf(ref);
        return status == null || WorkOrderStatus.CLOSED.name().equals(status);
    }

    @Override
    public void raisePriority(String ref, DevAlarm alarm, String priority, String reason) {
        woOps.raisePriority(ref, priority, reason);
    }

    @Override
    public void merge(String ref, DevAlarm lower, String intoRef, DevAlarm upper, String reason) {
        woOps.mergeInto(ref, intoRef, reason);
    }

    @Override
    public void annotate(String ref, DevAlarm alarm, String note) {
        woOps.annotate(ref, note);
    }

    @Override
    public void recovered(String ref, DevAlarm alarm, String note) {
        String status = woOps.statusOf(ref);
        if (WorkOrderStatus.CREATED.name().equals(status) || WorkOrderStatus.DISPATCHED.name().equals(status)) {
            woOps.withdraw(ref, note);
        } else {
            woOps.markAlarmRecovered(ref, LocalDateTime.now());
        }
    }
}
