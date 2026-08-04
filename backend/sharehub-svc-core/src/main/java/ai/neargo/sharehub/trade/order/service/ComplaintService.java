package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintCreateReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderComplaint;

/**
 * 投诉订单业务（{@code ord_complaint}）。
 *
 * <p>三个入口：C 端提交（争议类自动派生）、客服代客登记 {@link #create}、投诉处理 {@link #handle}。
 * {@link #toWorkOrder} **必须幂等**：{@code wo_no} 已有值就返回已有值，不重复开单
 * （[db-design §1.6]，底层还有 {@code wo_order.source_ref} UNIQUE 兜底）。
 */
public interface ComplaintService {

    PageResult<OrderComplaint> page(Integer page, Integer size, String keyword, String status, String issueType);

    /** 客服代客登记（电话/线下投诉）。 */
    OrderComplaint create(ComplaintCreateReq req);

    /** 处理：写 resolution + 处理人留痕（处理人由服务端回填）。 */
    OrderComplaint handle(String complaintNo, ComplaintHandleReq req);

    /** 转工单，**幂等**：已转过则原样返回既有投诉（含 {@code workOrderNo}），不再开单。 */
    OrderComplaint toWorkOrder(String complaintNo);
}
