package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.IngestResult;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备事件南向入口（{@code POST /internal/events/device}）：接入网关推送归一化后的设备信号。
 * 安全链：{@code /internal/events/**} 只认服务凭证（X-Internal-Token → ROLE_INTERNAL），不认员工令牌。
 *
 * <p>网关建成前，这也是测试与联调注入信号的唯一入口 —— 不另开「模拟」后门。
 */
@RestController
@RequestMapping("/internal/events")
public class DeviceEventInternalController {

    private final DeviceSignalService signals;

    public DeviceEventInternalController(DeviceSignalService signals) {
        this.signals = signals;
    }

    @PostMapping("/device")
    public IngestResult device(@RequestBody DeviceEventBatch batch) {
        return signals.ingest(batch);
    }
}
