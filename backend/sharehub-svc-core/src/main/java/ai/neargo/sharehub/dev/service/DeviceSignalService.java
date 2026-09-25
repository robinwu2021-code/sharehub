package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.IngestResult;

/**
 * 设备信号入口（TDD-运营核心流程/04 §4.5）。网关推送与测试注入共用 —— 不另开「模拟」后门。
 *
 * <p>每条事件独立事务、按 eventId 去重（EventIdempotency）：一条坏事件不拖垮整批，网关重推不重复执行保护动作。
 */
public interface DeviceSignalService {

    IngestResult ingest(DeviceEventBatch batch);

    /** 信号字典（只读）。 */
    java.util.List<ai.neargo.sharehub.dev.dto.DeviceOpsDtos.SignalCode> codes();
}
