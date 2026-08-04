package ai.neargo.sharehub.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.DeviceLogRow;

/**
 * 设备日志双流读服务（[api/README §3.2「设备日志」]）。
 *
 * <p><b>读模型，不建表</b>：一行日志来自 {@code gw_command_log}（下发，{@code stream=COMMAND/direction=DOWN}）
 * 与 {@code gw_message_log}（上报，{@code stream=REPORT/direction=UP}）的 <b>UNION</b>，
 * 按 {@code occurred_at} 归并到同一条时间轴 —— 排障时「发了什么指令 → 设备回了什么」因果可见，
 * 这是与竞品「只有设备上报」的差异点（见 ops-web {@code lib/types/device.ts} 的 DeviceLog 注释）。
 */
public interface DeviceLogService {

    /**
     * 分页查双流日志。
     *
     * @param stream {@code COMMAND} / {@code REPORT}；空表示两流合并
     * @param from   起始时间（含），空不限
     * @param to     结束时间（含），空不限
     */
    PageResult<DeviceLogRow> page(Integer page, Integer size, String cabinetNo, String stream,
                                  String from, String to);
}
