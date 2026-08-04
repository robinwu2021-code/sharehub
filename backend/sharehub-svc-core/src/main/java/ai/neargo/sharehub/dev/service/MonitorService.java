package ai.neargo.sharehub.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.CabinetMonitorRow;
import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;

import java.util.List;

/**
 * 实时监控（遥测读服务）：设备影子 + 心跳。
 *
 * <p>对应读模型 {@code [读] CabinetMonitor = dev_cabinet ⋈ dev_shadow}（[db-design §3.1]），**不建表**。
 * 影子的权威存储是 Redis，本服务读的是 DB 兜底快照。
 */
public interface MonitorService {

    /** 实时监控分页：keyword 匹配 cabinet_no；online 为 {@code true/false} 时按在线态筛选。 */
    PageResult<CabinetMonitorRow> monitor(Integer page, Integer size, String keyword, Boolean online);

    /** 某机柜最近 N 条心跳（遥测明细，按 beat_at 倒序）。 */
    /**
     * 某机柜最近的心跳。
     *
     * <p><b>返回 api 层的 {@link HeartbeatRecord} 而不是网关的实体</b>：
     * 心跳表归 device-gateway 所有（ADR-018），core 的方法签名里出现它的实体，
     * 就等于 Port 白抽了 —— 拆 Maven 模块时这一行就编译不过。
     */
    List<HeartbeatRecord> heartbeats(String cabinetNo, Integer limit);
}
