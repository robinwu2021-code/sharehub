package ai.neargo.sharehub.api.gateway.port;

import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;

import java.util.List;

/**
 * 遥测查询 —— device-gateway 暴露给其它服务的只读面。
 *
 * <p>心跳/报文是南向产物，归 device-gateway 所有（ADR-018）。
 * 运维监控要展示它们，但**不该直连它的表** —— 那样网关换存储（如心跳挪到时序库）
 * 会连带改运维侧代码。
 */
public interface TelemetryQueryPort {

    /** 取某机柜最近的心跳，按时间倒序。{@code limit} 上限由实现方钳制。 */
    List<HeartbeatRecord> recentHeartbeats(String cabinetNo, Integer limit);
}
