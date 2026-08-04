package ai.neargo.sharehub.gw.port;

import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import ai.neargo.sharehub.gw.entity.DevHeartbeat;
import ai.neargo.sharehub.gw.mapper.HeartbeatMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link TelemetryQueryPort} 的本地实现，住在 device-gateway 侧。
 *
 * <p>{@code limit} 的钳制放在**实现方**而不是调用方：上限是存储的承受能力，
 * 属于本服务的知识；放调用方等于每个调用者都要知道网关的容量。
 */
@Service
public class LocalTelemetryQuery implements TelemetryQueryPort {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final HeartbeatMapper heartbeats;

    public LocalTelemetryQuery(HeartbeatMapper heartbeats) {
        this.heartbeats = heartbeats;
    }

    @Override
    public List<HeartbeatRecord> recentHeartbeats(String cabinetNo, Integer limit) {
        int n = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return heartbeats.selectList(new LambdaQueryWrapper<DevHeartbeat>()
                        .eq(DevHeartbeat::getCabinetNo, cabinetNo)
                        .orderByDesc(DevHeartbeat::getBeatAt)
                        .last("limit " + n))
                .stream()
                .map(h -> new HeartbeatRecord(h.getCabinetNo(), h.getBeatAt(), h.getMetrics()))
                .toList();
    }
}
