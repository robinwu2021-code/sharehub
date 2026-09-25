package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRecord;
import ai.neargo.sharehub.alarm.service.AlarmService;
import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.TimelineItem;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoQuery;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDetail;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工单 × 告警的跨域编排（TDD-运营核心流程/06 §五）。
 *
 * <p>放在 portal 层而不是 wo 里：列表的「关联告警数」与详情的「关联告警」要同时读两个域，
 * 而 wo 不认识 alarm（依赖方向是 alarm → wo）。编排在这里，两个域都不必互相 import。
 */
@Component
public class WorkOrderAssembler {

    /** 工单详情：wo 侧的行 + 时间线 + 照片，加上关联的业务告警。 */
    public record WorkOrderDetailView(WorkOrder order, List<TimelineItem> timeline, List<FileRef> photos,
                                      List<AlarmRecord> alarms) {
    }

    private final WoOpsService workOrders;
    private final AlarmService alarms;

    public WorkOrderAssembler(WoOpsService workOrders, AlarmService alarms) {
        this.workOrders = workOrders;
        this.alarms = alarms;
    }

    public PageResult<WorkOrder> page(WoQuery q) {
        PageResult<WorkOrder> r = workOrders.page(q);
        if (r.getList().isEmpty()) return r;
        Map<String, Integer> counts = alarms.countByWorkOrders(r.getList().stream().map(WorkOrder::woNo).toList());
        return new PageResult<>(r.getList().stream().map(w -> w.withAlarmCount(counts.getOrDefault(w.woNo(), 0))).toList(),
                r.getTotal());
    }

    public WorkOrderDetailView detail(String woNo) {
        WorkOrderDetail d = workOrders.detail(woNo);   // 带范围读：看不到工单就到此为止
        List<AlarmRecord> linked = alarms.byWorkOrder(woNo);
        return new WorkOrderDetailView(d.order().withAlarmCount(linked.size()), d.timeline(), d.photos(), linked);
    }
}
