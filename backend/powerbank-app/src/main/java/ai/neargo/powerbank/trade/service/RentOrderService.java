package ai.neargo.powerbank.trade.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.OkResult;
import ai.neargo.powerbank.dto.Dto.RentOrder;
import ai.neargo.powerbank.dto.Dto.RentResult;

/**
 * 租借订单业务：运营端订单管理 + C 端借还闭环。
 * 权限在 Controller（运营端 @PreAuthorize / C 端属主鉴权 ConsumerContext）；本层只做业务与上下文取值。
 */
public interface RentOrderService {

    /** 运营端：全部订单分页（keyword=订单号/用户号；status 过滤）。 */
    PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status);

    /** C 端：我的订单（按属主 c_user_no 过滤）。 */
    PageResult<RentOrder> pageByOwner(String cUserNo, Integer page, Integer size);

    /** 订单详情。 */
    RentOrder detail(String orderNo);

    /** C 端借出：创单→免押预授权（骨架）→弹仓（骨架）；订单置 IN_USE。 */
    RentResult rent(String cUserNo, String cabinetNo);

    /** 归还结单（设备归还事件驱动）：IN_USE→RETURNED→计费 SETTLE→SETTLED。 */
    OkResult returnOrder(String orderNo, String returnCabinetNo);

    /** 客服干预：强制关单（免单/补偿为骨架）。 */
    OkResult intervene(String orderNo, String action);
}
