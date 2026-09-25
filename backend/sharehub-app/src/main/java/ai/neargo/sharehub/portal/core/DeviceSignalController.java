package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.SignalCode;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 设备信号字典（只读）。信号是设备层的止损依据，与业务告警码分开（告警码业务视角，2026-09-25 定）。 */
@RestController
@RequestMapping("/api/ops/device-signals")
public class DeviceSignalController {

    private final DeviceSignalService signals;

    public DeviceSignalController(DeviceSignalService signals) {
        this.signals = signals;
    }

    @GetMapping
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public List<SignalCode> codes() {
        return signals.codes();
    }
}
