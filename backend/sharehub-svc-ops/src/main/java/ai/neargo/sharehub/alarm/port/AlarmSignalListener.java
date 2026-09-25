package ai.neargo.sharehub.alarm.port;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 设备信号 → 业务告警（事件型判定：电池隐患、过热）。设备域已按字典执行了保护动作，这里只管「是不是业务告警」。
 * 引擎内部自带事务边界；不吞异常 —— 丢了由 alarm-rule-eval 的锁仓兜底补开。
 */
@Component
public class AlarmSignalListener {

    private final AlarmEngine engine;

    public AlarmSignalListener(AlarmEngine engine) {
        this.engine = engine;
    }

    @EventListener
    public void on(DeviceSignalEvent e) {
        engine.onEvent(e, LocalDateTime.now());
    }
}
