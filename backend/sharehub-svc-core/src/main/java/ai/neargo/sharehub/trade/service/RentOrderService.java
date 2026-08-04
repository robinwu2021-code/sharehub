package ai.neargo.sharehub.trade.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentResult;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.dto.InterventionDtos.InterveneReq;
import ai.neargo.sharehub.trade.dto.InterventionDtos.OrderIntervention;
import ai.neargo.sharehub.trade.dto.InterventionDtos.OrderInterveneResult;

/**
 * 租借订单业务：运营端订单管理 + C 端借还闭环。
 * 权限在 Controller（运营端 @PreAuthorize / C 端属主鉴权 ConsumerContext）；本层只做业务与上下文取值。
 */
public interface RentOrderService {

    /** 运营端：全部订单分页（keyword=订单号/用户号；status 过滤）。 */
    PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status);

    /** C 端：我的订单（按属主 c_user_no 过滤）。 */
    PageResult<RentOrder> pageByOwner(String cUserNo, Integer page, Integer size);

    /** 订单详情（**受数据范围约束**：运营端 AGENT 只能取到自己归属的单）。 */
    RentOrder detail(String orderNo);

    /**
     * C 端订单详情：**显式豁免数据范围**，由调用方 {@code ConsumerContext.assertOwner} 做属主判定。
     *
     * <p>为什么要单开一个方法而不是复用 {@link #detail(String)}：
     * {@code ord_order} 注册数据范围后，非属主查询在 SQL 层就被过滤成空，
     * 属主守卫拿不到行、无从判定，结果从「403 无权」退化成「400 不存在」。
     *
     * <p>为什么<b>不能</b>直接给 {@link #detail(String)} 加豁免：
     * 运营端 {@code GET /api/trade/orders/&#123;orderNo&#125;} 走的就是它，且那条路径<b>没有</b>属主/归属守卫 ——
     * 一旦豁免，AGENT 就能读到其它代理的订单详情。豁免必须只发生在「有显式守卫兜底」的这一条路径上。
     */
    RentOrder detailForConsumer(String orderNo);

    /** C 端借出：创单→免押预授权（骨架）→弹仓（骨架）；订单置 IN_USE。 */
    RentResult rent(String cUserNo, String cabinetNo);

    /** 归还结单（设备归还事件驱动）：IN_USE→RETURNED→计费 SETTLE→SETTLED。 */
    OkResult returnOrder(String orderNo, String returnCabinetNo);

    /**
     * 客服干预：按 {@link InterventionDtos#RULES} 迁移状态，并**同事务写审计留痕**。
     *
     * <p>改造前这里无论什么动作都把订单置 {@code CLOSED} 且不留痕 ——
     * 「免单」会顺手关掉一张还在使用中的单，而且事后查不到是谁做的。
     */
    OrderInterveneResult intervene(String orderNo, InterveneReq req);

    /** 干预留痕分页（审计视图）。 */
    PageResult<OrderIntervention> interventions(Integer page, Integer size, String orderNo, String action);

    /** 按用户批量数累计单量（用户列表/档案的 {@code orders} 回填；缺失键 = 0 单不出现）。 */
    java.util.Map<String, Long> countByUsers(java.util.Collection<String> cUserNos);

    /** 进行中订单（C 端首页快捷入口）：该用户最新一笔 {@code IN_USE}；无则 {@code null}。 */
    RentOrder ongoingOf(String cUserNo);

    /**
     * C 端买断：不还了，按买断价（{@code sys_biz_rule} 计费兜底分区）结单。
     * 仅 {@code IN_USE} 可买断；属主校验由门面 {@code ConsumerContext} 做。
     *
     * @param buyoutPrice 买断价（门面从 BizRules 取，服务不反向依赖 platform 域）
     */
    RentOrder buyout(String orderNo, java.math.BigDecimal buyoutPrice);
}
