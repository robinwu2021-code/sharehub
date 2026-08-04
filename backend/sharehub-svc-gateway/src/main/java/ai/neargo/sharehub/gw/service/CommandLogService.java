package ai.neargo.sharehub.gw.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.gw.dto.GwDtos.CommandRecord;

/**
 * 指令记录查询（[api/README §3.2] {@code GET /api/ops/command-records}）。
 *
 * <p>**只读**：指令下发本身归 access-gateway 南向链路（ADR-001），运营端在这里看结果，不在这里发指令
 * （发指令走已有的 {@code POST /api/ops/cabinets/{no}/commands}）。
 */
public interface CommandLogService {

    /** 指令记录分页。筛选：机柜 / 指令类型 / 状态。 */
    PageResult<CommandRecord> page(Integer page, Integer size, String keyword,
                                   String cabinetNo, String type, String status);

    /** 按幂等键单查；不存在返回 {@code null}。重试方据此判断「这条指令是不是已经发过」。 */
    CommandRecord getByCommandId(String commandId);
}
