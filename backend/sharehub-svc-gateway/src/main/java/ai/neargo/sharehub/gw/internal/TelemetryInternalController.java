package ai.neargo.sharehub.gw.internal;

import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@link TelemetryQueryPort} 的服务端（仅服务间调用）。 */
@RestController
public class TelemetryInternalController {

    private final TelemetryQueryPort telemetry;

    public TelemetryInternalController(TelemetryQueryPort telemetry) {
        this.telemetry = telemetry;
    }

    @GetMapping("/internal/gw/heartbeats")
    public List<HeartbeatRecord> heartbeats(@RequestParam String cabinetNo,
                                            @RequestParam(required = false) Integer limit) {
        return telemetry.recentHeartbeats(cabinetNo, limit);
    }
}
