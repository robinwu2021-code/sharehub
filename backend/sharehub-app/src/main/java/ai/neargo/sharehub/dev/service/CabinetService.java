package ai.neargo.powerbank.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.Cabinet;
import ai.neargo.powerbank.dto.Dto.CabinetDetail;
import ai.neargo.powerbank.dto.Dto.CommandResult;

import java.util.Map;

/** 设备业务：柜机列表/详情(含派生仓位)/远程指令(转 access-gateway 占位)。 */
public interface CabinetService {
    PageResult<Cabinet> page(Integer page, Integer size, String keyword, String onlineStatus, String status);
    CabinetDetail detail(String cabinetNo);
    CommandResult sendCommand(String cabinetNo, String type, Map<String, Object> params);
}
