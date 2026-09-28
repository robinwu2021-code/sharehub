package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.port.OpsKitAlarmTicketPort;
import ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand;
import ai.neargo.sharehub.common.event.DomainEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 切换期（方案 23 §十）：开关打开前的本地工单（WO…）仍按本地处理；新开的 OpsKit 单（AL…）走命令 */
class OpsKitAlarmTicketPortTransitionTest {

    final List<DomainEvent> published = new ArrayList<>();
    final DomainEventBus bus = published::add;
    final WoOpsService wo = mock(WoOpsService.class);
    final OpsKitAlarmTicketPort port = new OpsKitAlarmTicketPort(bus, wo);
    final DevAlarm a = alarm("ALM1");

    static DevAlarm alarm(String no) {
        DevAlarm x = new DevAlarm();
        x.setAlarmNo(no);
        return x;
    }

    @Test
    @DisplayName("本地单号：恢复 / 升级 / 说明走本地工单服务，不发任何 OpsKit 命令")
    void localRefsStayLocal() {
        when(wo.statusOf("WO1")).thenReturn("DISPATCHED");
        port.recovered("WO1", a, "关联告警已自动恢复");
        port.raisePriority("WO1", a, "HIGH", "升级");
        port.annotate("WO1", a, "说明");
        verify(wo).withdraw("WO1", "关联告警已自动恢复");
        verify(wo).raisePriority("WO1", "HIGH", "升级");
        verify(wo).annotate("WO1", "说明");
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("OpsKit 单号：发命令（优先级换成类型的 P 档）；一本地一 OpsKit 不并单、只在本地单上留说明")
    void opskitRefsBecomeCommands() {
        port.recovered("AL01", a, "关联告警已自动恢复");
        port.raisePriority("AL01", a, "URGENT", "升级");
        assertThat(published).extracting(e -> ((OpsKitTicketCommand) e).command()).containsExactly("ALARM_RECOVERED", "RAISE_PRIORITY");
        assertThat(((OpsKitTicketCommand) published.get(1)).args()).containsEntry("priority", "P1");

        published.clear();
        port.merge("WO9", a, "AL02", alarm("ALM2"), "告警 ALM1 并入 ALM2");
        verify(wo, never()).mergeInto(anyString(), anyString(), anyString());
        verify(wo).annotate(eq("WO9"), org.mockito.ArgumentMatchers.contains("未并单"));
        assertThat(published).isEmpty();
    }
}
