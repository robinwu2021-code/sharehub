package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.DeviceLogRow;
import ai.neargo.sharehub.dev.service.DeviceLogService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备日志双流实现 —— <b>本分片为占位空实现</b>。
 *
 * <p><b>TODO（跨分片依赖）</b>：真实实现需 UNION 两张 gw 域表，而 {@code gw_command_log} /
 * {@code gw_message_log} 的实体与 mapper 属于**网关分片**，本分片不得越界新建（骨架规约 §0.1）。
 * gw 域落地后按下述形态补齐：
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
