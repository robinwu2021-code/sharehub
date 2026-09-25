package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.DeviceLogRow;
import ai.neargo.sharehub.dev.service.DeviceLogService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备日志双流实现 —— <b>本分片为占位空实现</b>。
 *
 * <p><b>⚠️ 原先这段 TODO 写的阻塞点已经不成立了，别照着它动手。</b>
 * 它说「gw 域表尚未落地」——{@code gw_command_log} / {@code gw_message_log} 的建表
 * （V2）、实体、mapper、以及查指令的 {@code CommandLogService} <b>早就都在了</b>。
 * 照这段 TODO 把 UNION 写出来，页面照样是空的，然后才会发现真正的原因。
 *
 * <p><b>真正的阻塞点：这两张表没有任何写入方。</b> 全仓库 grep 不到一处
 * {@code insert}：下发指令的两处（{@code CabinetServiceImpl.sendCommand} 与
 * {@code RentOrderServiceImpl.rent}）都是 {@code "CMD" + System.nanoTime()} 当回执，
 * 不落库；设备上报也没有落 {@code gw_message_log} 的路径。
 * 所以补齐顺序是 <b>①先让下发与上报真的落日志，②再做这里的 UNION</b>，
 * 反过来做等于给一张永远空的表写查询。
 *
 * <p>跨分片那半仍然成立，但解法已经有先例：走 api 模块的 Port
 * （{@code api/gateway/port/TelemetryQueryPort} 就是这么给运维侧用心跳的），
 * 不是「不得新建」而是「不直连它的表」。
 *
 * <p>两条都齐了之后，查询按下述形态补齐：
 * <pre>
 * SELECT command_id AS log_no, cabinet_no, 'COMMAND' AS stream, 'DOWN' AS direction,
 *        type AS event_type, payload, vendor_code, created_at AS occurred_at, result
 *   FROM gw_command_log  WHERE ...
 * UNION ALL
 * SELECT msg_id, cabinet_no, 'REPORT', 'UP', event_type, payload, vendor_code, received_at, 'OK'
 *   FROM gw_message_log  WHERE ...
 * ORDER BY occurred_at DESC
 * </pre>
 * 两表都是 append 且按月分区，UNION 必须带 {@code from/to} 下推到各自分区键，否则会全表扫。
 * 分页建议走自定义 XML/{@code @Select} 的 {@code Page<DeviceLogRow>}，不要在内存里合并两页。
 */
@Service
public class DeviceLogServiceImpl implements DeviceLogService {

    @Override
    public PageResult<DeviceLogRow> page(Integer page, Integer size, String cabinetNo, String stream,
                                         String from, String to) {
        // 占位：gw 域表尚未落地，先返空页，保证前端「设备日志」页可联调不报错。
        return new PageResult<>(List.of(), 0L);
    }
}
