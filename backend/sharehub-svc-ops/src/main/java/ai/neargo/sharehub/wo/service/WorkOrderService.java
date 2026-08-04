package ai.neargo.sharehub.wo.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;

/** 工单业务：列表/看板筛选 + 派单（状态机流转 CREATED→DISPATCHED）。 */
public interface WorkOrderService {
    PageResult<WorkOrder> page(Integer page, Integer size, String keyword, String status, String type);
    OkResult dispatch(String woNo, String assignee);
}
