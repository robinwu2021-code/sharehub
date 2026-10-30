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

    /** 同上，另可按收款态筛（PAID / UNPAID，C-BL-04）—— 运营催缴要能一眼筛出欠费单。 */
    PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status, String payStatus);

    /**
     * 运营端订单列表的完整筛选（第 5 批，运营方原文 RNT-03 / R02 / MON-06）：
     * 借出日期区间（业务日，含两端）、商家、站点、设备（借出或归还柜）。
     */
    record OrderFilter(String keyword, String status, String payStatus, java.time.LocalDate from, java.time.LocalDate to,
                       String agentNo, String siteNo, String cabinetNo) {
    }

    PageResult<RentOrder> pageAdmin(Integer page, Integer size, OrderFilter f);

    /** C 端：我的订单（按属主 c_user_no 过滤）。 */
    PageResult<RentOrder> pageByOwner(String cUserNo, Integer page, Integer size);

    /**
     * 某用户的累计消费。**一次聚合查询**，不是「取一页再累加」。
     *
     * <p>用户 360 档案此前是 {@code pageByOwner(cUserNo, 1, 200)} 之后把那一页的
     * {@code feeAmount} 相加，而单数取的是 {@code getTotal()}（全量）。于是运营端并排
     * 渲染的「{@code {count} 单 · {amount}}」里，<b>单数对、金额在 200 单之后就不再增长</b>，
     * 且没有任何地方会提示 —— 只有拿它去核对的人才会发现。
     *
     * @param count    订单总数（与 {@code amount} 出自同一次查询，两个数不可能对不上）
     * @param amount   累计金额。⚠️ <b>跨币种相加是无意义的</b> —— 今天单市场（AED）成立，
     *                 将来多币种时这个口径要改成按币种分组（沿用此前语义，本次只修截断）
     * @param currency 最近一单的币种；无单时为 {@code null}，由调用方兜底
     */
    record OwnerSpend(long count, java.math.BigDecimal amount, String currency) {
    }

    /** 见 {@link OwnerSpend}。无单时返回 {@code count=0 / amount=0 / currency=null}。 */
    OwnerSpend spendOf(String cUserNo);

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
    /**
     * 扫码借出。
     *
     * @param useFreeDeposit 用户在确认页选的那一项。<b>此前这个选择根本没传到后端</b> ——
     *                       控制器只读 {@code cabinetNo}，于是无论选哪个，订单都记 50 押金。
     *                       选「免押金」的人先被冻结了一笔预授权（{@code pay_auth}），
     *                       订单上**又**记一笔押金，同一笔钱在他眼里出现两次。
     * @param couponNo       用券；null/空 表示不用券。传了一张用不了的券会**拒单**，
     *                       不是静默按原价 —— 后者要等到结算扣款时用户才发现。
     */
    RentResult rent(String cUserNo, String cabinetNo, boolean useFreeDeposit, String couponNo);

    /** 归还结单（设备归还事件驱动）：IN_USE→RETURNED→计费 SETTLE→SETTLED。 */
    OkResult returnOrder(String orderNo, String returnCabinetNo);

    /**
     * 按指定时刻结单（业务告警自愈 RETURN_NOT_RECOGNIZED：宝已在柜中被识别，但归还事件丢了）。
     * 计费截止到宝首次在柜中出现的时刻，而不是发现问题的时刻 —— 用户不为系统的延迟付钱。
     *
     * @param endUtc 计费截止（UTC）；null = 现在
     */
    OkResult returnOrderAt(String orderNo, String returnCabinetNo, java.time.LocalDateTime endUtc);

    /**
     * 出宝回执：DISPENSING → IN_USE，**计费从这一刻才有意义**。
     *
     * <p>由设备信号推进（{@code EJECT_OK} / {@code COMMAND_RESULT=OK}），
     * 或在没有真机的环境里由 {@code EjectSimulation} 立刻补一次。
     * 订单已不在出宝中时返回 false（幂等：回执重复、或已被超时撤单抢先）。
     */
    boolean confirmDelivered(String orderNo, String reason);

    /**
     * 找这台柜机上**正在等出宝**的那一单（最近的一单）。设备回执只带柜机/仓位，不带订单号。
     *
     * @param slotIndex 给了就按仓位精确匹配；null = 只按柜机（仓位级上报未接入的柜）
     */
    String pendingDeliveryOrder(String cabinetNo, Integer slotIndex);

    /**
     * 撤销出宝失败的订单（DISPENSING → CLOSED，不收费）。订单已不在出宝中时返回 false（幂等：别人已处理）。
     */
    boolean cancelUndelivered(String orderNo, String reason);

    /**
     * 进行中订单的实时试算：按「现在」的时长跑一遍计价链，**只读** ——
     * 不核销券、不扣免单额度、不写库。非 {@code IN_USE} 返回 null。
     *
     * <p>口径与 {@code returnOrderAt} 的结算逐项一致（同一套快照 + 倍率 + 券 + 免单 + 封顶），
     * 否则用户看到的数与最终账单对不上，而那正是他会去投诉的那一种「对不上」。
     */
    ai.neargo.sharehub.trade.dto.TradeLegacyDtos.FeeEstimate estimateNow(String orderNo);

    /** 逾期达封顶自动买断：触顶则以封顶价买断并返回 true（执行清单 A1 · E17）。 */
    boolean buyoutIfCapped(String orderNo);

    /**
     * 到最长时限发起最高收费（V180，{@code rent.overdue.mode=TIME_LIMIT}，运营方原文 RQ-BIL-02）。
     *
     * <p>借出时长达到 {@code hours} 的使用中订单：按计费方案总封顶请款，订单进 {@code OVERDUE_CHARGED}
     * 「已扣款未归还」，宝仍算在用户手里。之后归还只结单、不冲销（RQ-BIL-04 / AC-03）。
     *
     * @return 这一单是否在本次发起了最高收费
     */
    boolean chargeOverdueIfDue(String orderNo, int hours);

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

    /**
     * 补缴欠费单（C-BL-04）：只接受已结算且欠费的单；已收的单 409，不重复收钱。
     * 金额取订单应付。成功后订单转已收并发结算事件（分润随之生成）。
     */
    ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry repay(String orderNo, String payType);

    /**
     * 有欠费单就抛 409 {@code error.rent.arrears}（带 {@code data:{orderNo, amount}}）。
     * 借出前与免押预授权前都要查：欠费的人借不了，就不该先在他卡上冻一笔押金。
     */
    void requireNoArrears(String cUserNo);
}
