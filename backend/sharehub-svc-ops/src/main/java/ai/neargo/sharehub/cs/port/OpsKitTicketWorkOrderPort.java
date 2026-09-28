package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.api.platform.flow.TicketRequestedEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.cs.entity.CsTicket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报障转工单 → OpsKit 工单（M5-3，方案 23 §五）。开关 {@code sharehub.flow.cs-ticket.engine=OPSKIT} 时替换本地实现。
 *
 * <p><b>异步</b>：发 {@link TicketRequestedEvent}（与报障单同事务落 outbox），提交后送 OpsKit，工单号由
 * {@link CsTicketOutcome} 回填到 {@code wo_no}。所以这里返回 {@code null} —— 此刻还没有号，
 * 调用方写进去的空值很快会被回填覆盖（通常在同一个请求的事务提交后几十毫秒内）。
 *
 * <p>幂等：同一张报障单无论重投、还是客服反复点「转工单」，OpsKit 幂等键都是 {@code opskit:ticket:cs_ticket:<单号>}，
 * 拿到的是同一张工单。
 *
 * <p>建到「设备报修」类型：来源 APP（C 端报障）/ CS（客服手工转），来源单号 = 报障单号；
 * 业务对象是报障单本身（回写与按对象对账都按它找），机柜写进标题与描述。
 */
@Component
@ConditionalOnProperty(name = "sharehub.flow.cs-ticket.engine", havingValue = "OPSKIT")
public class OpsKitTicketWorkOrderPort implements TicketWorkOrderPort {

    static final String BIZ_TYPE = "cs_ticket";
    static final String TYPE_CODE = "repair";

    private final DomainEventBus events;

    public OpsKitTicketWorkOrderPort(DomainEventBus events) {
        this.events = events;
    }

    @Override
    public String createWorkOrder(CsTicket t) {
        String issue = t.getIssue() == null || t.getIssue().isBlank() ? "（用户未填写描述）" : t.getIssue();
        String cabinet = t.getCabinetNo() == null ? "" : " · 机柜 " + t.getCabinetNo();
        Map<String, Object> fields = new LinkedHashMap<>();
        // C 端来的走 APP，客服手工转的走 CS（来源列据此显示「用户报障 / 客服」）
        fields.put("source", "APP".equalsIgnoreCase(t.getChannel()) || t.getCUserNo() != null ? "APP" : "CS");
        fields.put("sourceRef", t.getTicketNo());
        events.publish(new TicketRequestedEvent(BIZ_TYPE, t.getTicketNo(), TYPE_CODE,
                truncate("用户报障" + cabinet + "：" + issue, 80),
                "报障单 " + t.getTicketNo() + cabinet + "\n" + issue,
                null, fields, "报障 " + t.getTicketNo() + cabinet, List.of(),
                // 同一台机柜的未结报障并成一张：用户连着报三次「借不出」，运维只该收到一张
                t.getCabinetNo() == null ? null : "cs:cabinet:" + t.getCabinetNo(),
                null, null));
        return null;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
