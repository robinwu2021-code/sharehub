package ai.neargo.sharehub.trade.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentResult;
import ai.neargo.sharehub.trade.OrdStateMachine;
import ai.neargo.sharehub.trade.OrderStatus;
import ai.neargo.sharehub.trade.entity.OrdIntervention;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.InterventionMapper;
import ai.neargo.sharehub.trade.order.entity.OrdRentExt;
import ai.neargo.sharehub.trade.order.mapper.OrdRentExtMapper;
import ai.neargo.sharehub.trade.price.engine.PriceEngine;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.trade.price.engine.ChargeChain;
import ai.neargo.sharehub.trade.price.engine.PriceMultiplierResolver;
import ai.neargo.sharehub.trade.price.engine.PriceQuery;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.trade.service.RentOrderService.OwnerSpend;
import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 租借订单业务实现。借还走独立状态机 {@link OrdStateMachine}；押金/支付/弹仓为骨架（ADR-005 委托 nearpay）。 */
@Service
public class RentOrderServiceImpl implements RentOrderService {

    private static final double DEPOSIT = 50.0;      // 免押额度（AED），骨架
    private static final String CURRENCY = "AED";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RentOrderServiceImpl.class);

    /** 归还/结算由设备事件与系统驱动，不是某个人在操作。 */
    private static final String OPERATOR_SYSTEM = "SYSTEM";

    /** 订单主状态机的流水。此前 ord_event_log 只有运营干预在写，正常生命周期一条都没有。 */
    private final ai.neargo.sharehub.trade.order.service.OrderEventLogService eventLog;
    private final OrdMapper mapper;
    private final OrdStateMachine stateMachine;
    private final ai.neargo.sharehub.dev.port.CabinetCommandPort cabinetCommands;
    private final ai.neargo.sharehub.trade.EjectSimulation ejectSimulation;
    private final OrdRentExtMapper rentExtMapper;
    private final InterventionMapper interventionMapper;
    private final PriceResolver priceResolver;
    private final PriceEngine priceEngine;
    private final ChargeChain chargeChain;
    private final CabinetMapper cabinetMapper;
    private final DomainEventBus eventBus;
    private final PriceMultiplierResolver multipliers;
    /** 站点的场地方 / 场景 / 区域在 platform；跨服务只读面（ADR-017 §5.2）。可缺席 —— 见 {@link #queryOf}。 */
    private final ObjectProvider<SiteQueryPort> siteQuery;

    /** 停借保还（TDD-运营核心流程/04 §4.4）：只拦借，归还路径不调用。 */
    private final ai.neargo.sharehub.trade.RentabilityGuard rentability;

    /** 借还驱动充电宝状态（执行清单 A2）：借出挑宝 → RENTED，归还 → IN_CABINET，买断 → SOLD。 */
    private final ai.neargo.sharehub.dev.port.PowerbankTracker powerbankTracker;

    /**
     * 用券。走端口不走 {@code UserCouponService}：{@code user} 已经依赖 {@code trade}
     * （钱包读订单），反向直连即成包循环，{@code ArchitectureTest} 的硬规则会红。
     */
    private final ai.neargo.sharehub.api.core.port.CouponUsePort coupons;

    /** 免单白名单。同 {@link #coupons}，走端口是为了不把 trade 与 user 连成环。 */
    private final ai.neargo.sharehub.api.core.port.FreeRentPort freeRents;

    /** 会员权益（折扣 / 免费时长）。同 {@link #coupons}，走端口是为了不把 trade 与 user 连成环。 */
    private final ai.neargo.sharehub.api.core.port.MemberRightsPort memberRights;

    /** 分润归属期间是**业务日历**上的月份 —— 见 publishSettled 与 BizClock。 */
    private final ai.neargo.sharehub.common.time.BizClock clock;

    /** 结算即请款、补缴（C-BL-04）。 */
    private final ai.neargo.sharehub.trade.pay.service.PaymentService payments;

    public RentOrderServiceImpl(OrdMapper mapper, OrdStateMachine stateMachine,
                                OrdRentExtMapper rentExtMapper, InterventionMapper interventionMapper,
                                PriceResolver priceResolver, PriceEngine priceEngine,
                                ChargeChain chargeChain, CabinetMapper cabinetMapper,
                                DomainEventBus eventBus, PriceMultiplierResolver multipliers,
                                ObjectProvider<SiteQueryPort> siteQuery,
                                ai.neargo.sharehub.trade.order.service.OrderEventLogService eventLog,
                                ai.neargo.sharehub.trade.RentabilityGuard rentability,
                                ai.neargo.sharehub.dev.port.PowerbankTracker powerbankTracker,
                                ai.neargo.sharehub.api.core.port.CouponUsePort coupons,
                                ai.neargo.sharehub.api.core.port.FreeRentPort freeRents,
                                ai.neargo.sharehub.api.core.port.MemberRightsPort memberRights,
                                ai.neargo.sharehub.common.time.BizClock clock,
                                ai.neargo.sharehub.dev.port.CabinetCommandPort cabinetCommands,
                                ai.neargo.sharehub.trade.EjectSimulation ejectSimulation,
                                ai.neargo.sharehub.trade.pay.service.PaymentService payments) {
        this.payments = payments;
        this.cabinetCommands = cabinetCommands;
        this.ejectSimulation = ejectSimulation;
        this.clock = clock;
        this.freeRents = freeRents;
        this.memberRights = memberRights;
        this.coupons = coupons;
        this.powerbankTracker = powerbankTracker;
        this.rentability = rentability;
        this.eventLog = eventLog;
        this.multipliers = multipliers;
        this.siteQuery = siteQuery;
        this.cabinetMapper = cabinetMapper;
        this.eventBus = eventBus;
        this.chargeChain = chargeChain;
        this.rentExtMapper = rentExtMapper;
        this.interventionMapper = interventionMapper;
        this.priceResolver = priceResolver;
        this.priceEngine = priceEngine;
        this.mapper = mapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status) {
        return pageAdmin(page, size, keyword, status, null);
    }

    @Override
    public PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status, String payStatus) {
        int p = pg(page), s = sz(size);
        LambdaQueryWrapper<OrdOrder> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(OrdOrder::getOrderNo, keyword).or().like(OrdOrder::getCUserNo, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(OrdOrder::getStatus, status);
        if (payStatus != null && !payStatus.isBlank()) w.eq(OrdOrder::getPayStatus, payStatus);
        w.orderByDesc(OrdOrder::getId);
        Page<OrdOrder> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(enrich(r.getRecords()), r.getTotal());
    }

    @Override
    public PageResult<RentOrder> pageByOwner(String cUserNo, Integer page, Integer size) {
        int p = pg(page), s = sz(size);
        LambdaQueryWrapper<OrdOrder> w = new LambdaQueryWrapper<OrdOrder>()
                .eq(OrdOrder::getCUserNo, cUserNo).orderByDesc(OrdOrder::getId);
        Page<OrdOrder> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(RentOrderServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public OwnerSpend spendOf(String cUserNo) {
        /*
         * COUNT 与 SUM 出自同一次查询 —— 两个数并排显示时，它们必须不可能对不上。
         * 此前单数取全量 total、金额只累加第一页 200 行（见 OwnerSpend 的注释）。
         *
         * 用 QueryWrapper（字符串列名）而不是 LambdaQueryWrapper：聚合函数没有 lambda 形式。
         * @TableLogic 仍会补 deleted=0。
         */
        var w = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OrdOrder>()
                .select("COUNT(*) AS cnt", "COALESCE(SUM(amount), 0) AS amt")
                .eq("c_user_no", cUserNo);
        java.util.Map<String, Object> agg = mapper.selectMaps(w).stream().findFirst().orElse(java.util.Map.of());
        long count = agg.get("cnt") == null ? 0L : ((Number) agg.get("cnt")).longValue();
        java.math.BigDecimal amount = agg.get("amt") == null
                ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(agg.get("amt").toString());

        // 币种取最近一单（沿用此前「orders.get(0).currency()」的语义，那一页也是 id 倒序）
        String currency = count == 0 ? null : mapper.selectOne(new LambdaQueryWrapper<OrdOrder>()
                .select(OrdOrder::getCurrency).eq(OrdOrder::getCUserNo, cUserNo)
                .orderByDesc(OrdOrder::getId).last("limit 1")).getCurrency();
        return new OwnerSpend(count, amount, currency);
    }

    @Override
    public RentOrder detail(String orderNo) {
        return enrich(List.of(require(orderNo))).get(0);
    }

    /**
     * 运营端富装配：{@code waivedAmount} 直取列；补偿金额/弹出次数/最近弹出时刻
     * 由 {@code ord_intervention} 按单批量折叠（compensate 求和 / eject 计数+取末次）。
     */
    private List<RentOrder> enrich(List<OrdOrder> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(OrdOrder::getOrderNo).toList();
        java.util.Map<String, java.math.BigDecimal> compensate = new java.util.HashMap<>();
        java.util.Map<String, Integer> ejects = new java.util.HashMap<>();
        java.util.Map<String, String> lastEject = new java.util.HashMap<>();
        for (OrdIntervention i : interventionMapper.selectList(
                new LambdaQueryWrapper<OrdIntervention>().in(OrdIntervention::getOrderNo, nos)
                        .orderByAsc(OrdIntervention::getId))) {
            if ("compensate".equals(i.getAction()) && i.getAmount() != null) {
                compensate.merge(i.getOrderNo(), i.getAmount(), java.math.BigDecimal::add);
            }
            if ("eject".equals(i.getAction())) {
                ejects.merge(i.getOrderNo(), 1, Integer::sum);
                lastEject.put(i.getOrderNo(),
                        i.getCreatedAt() == null ? null : i.getCreatedAt().toString());
            }
        }
        return rows.stream().map(e -> {
            RentOrder base = toVO(e);
            return new RentOrder(base.orderNo(), base.cUserNo(), base.cabinetNo(), base.returnCabinetNo(),
                    base.powerbankNo(), base.locationName(), base.status(), base.rentStartAt(),
                    base.rentEndAt(), base.durationMin(), base.feeAmount(), base.depositAmount(),
                    base.currency(), e.getWaivedAmount(),
                    compensate.get(e.getOrderNo()), ejects.getOrDefault(e.getOrderNo(), 0),
                    lastEject.get(e.getOrderNo()), e.getCouponAmount(), e.getMemberDiscountAmount(), e.getPayStatus());
        }).toList();
    }

    @Override
    public RentOrder detailForConsumer(String orderNo) {
        // 豁免数据范围：C 端属主判定交给 ConsumerContext.assertOwner（见接口注释）
        return DataScopeContext.executeWithoutScope(() -> detail(orderNo));
    }

    @Override
    public RentOrder ongoingOf(String cUserNo) {
        OrdOrder e = DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(new LambdaQueryWrapper<OrdOrder>()
                        .eq(OrdOrder::getCUserNo, cUserNo)
                        .eq(OrdOrder::getStatus, "IN_USE")
                        .orderByDesc(OrdOrder::getId).last("limit 1")));
        return e == null ? null : toVO(e);
    }

    @Override
    public RentOrder buyout(String orderNo, java.math.BigDecimal buyoutPrice) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = require(orderNo);
            if (!"IN_USE".equals(e.getStatus())) {
                throw BizException.conflict("error.order.buyout_only_in_use", e.getStatus());
            }
            java.math.BigDecimal price = buyoutPrice == null ? java.math.BigDecimal.ZERO : buyoutPrice;
            e.setRentEndAt(nowUtc());
            e.setAmount(price);
            e.setFeeAmount(price.doubleValue());   // 过渡期双写，同 returnOrder
            e.setBuyout(1);
            // 买断即终局：RETURN→SETTLE 两跳走状态机，保持非法迁移仍会被拒
            String beforeBuyout = e.getStatus();
            e.setStatus(stateMachine.next(e.getStatus(), "RETURN"));
            e.setStatus(stateMachine.next(e.getStatus(), "SETTLE"));
            mapper.updateById(e);
            // 买断是两跳一次做完，只记一条 BUYOUT —— 记成 RETURN+SETTLE 会让时间线
            // 看起来像「他还了柜子然后结算」，而事实是他没还
            appendEvent(orderNo, beforeBuyout, e.getStatus(), "BUYOUT", e.getCUserNo());
            powerbankTracker.sold(e.getPowerbankNo());
            // 此前 App 发起的买断不发结算事件（只有逾期自动买断那条路补发）—— 那笔收入不分润
            collect(e, "BUYOUT");
            return toVO(e);
        });
    }

    /** 本服务只处理充电宝单；充电桩/储物柜由各自的 device 子包处理（ADR-018）。 */
    private static final String DEVICE_TYPE_POWERBANK = "POWERBANK";

    @Override
    public RentResult rent(String cUserNo, String cabinetNo, boolean useFreeDeposit, String couponNo) {
        if (cabinetNo == null || cabinetNo.isBlank()) throw BizException.badRequest("error.rent.cabinet_required");
        OrdOrder e = new OrdOrder();
        e.setOrderNo(IdGenerator.next("ORD"));
        e.setCUserNo(cUserNo);
        e.setCabinetNo(cabinetNo);
        e.setPowerbankNo(IdGenerator.next("PB"));
        /*
         * 落单为**出宝中**，不是直接 IN_USE。
         *
         * 2026-09-27 之前这里写的是 `setStatus("IN_USE")`，注释说「弹仓为骨架」——
         * 而代价不只是「暂时不发指令」：订单从不经过 DISPENSING，于是
         *   · C 端的「等弹出」无论怎么轮询都第一轮就成功（等于没在等）；
         *   · 出宝失败的自愈告警 RENT_NOT_DELIVERED（阈值 5 分钟、自动撤单、
         *     转客服兜底）配得好好的，**永远不会触发** —— 没有单会停在出宝中；
         *   · 状态机里 DISPENSING→CLOSED 那条边同样走不到。
         * 宝没弹出来而订单照常计费，系统里一处异常都没有。
         */
        e.setStatus(OrderStatus.DISPENSING.name());
        // ⚠️ 必须是 LocalDateTime 而非 Instant：ord_order.rent_start_at 是 DATETIME(3)，
        // MariaDB 不接受 Instant.toString() 产出的带 Z 字面量，写入直接
        // Data truncation: Incorrect datetime value → 500，即扫码借出**从未成功落库**。
        // 与 OrderSupport.now() 同一处理：只去掉 DB 不认的 Z，时钟仍取 UTC。
        e.setRentStartAt(nowUtc());
        // 免押：额度冻结在 pay_auth 上，订单**不记押金** —— 记了就是同一笔钱说两遍，
        // 而用户看到的是「我明明选了免押，怎么还有 50 押金」。
        e.setDepositAmount(useFreeDeposit ? 0.0 : DEPOSIT);
        e.setFeeAmount(0.0);
        e.setCurrency(CURRENCY);
        e.setTenantId("MAIN");

        // —— 共性列（ADR-018 拆表后新增）——
        // 迁移只回填了存量行；**写入口不设，新单就会缺列** —— 实测漏过一次：
        // V16 跑完 157 行齐备，测试新建的那 1 单 device_type/amount/started_at 全 NULL。
        e.setDeviceType(DEVICE_TYPE_POWERBANK);
        e.setSourceChannel("APP");
        e.setAmount(java.math.BigDecimal.ZERO);
        e.setStartedAt(java.time.LocalDateTime.now());

        /*
         * 快照计价规格：**展开后的结构**而非 planNo 引用 —— 改价不影响在途单。
         * 匹配不到方案会在这里抛异常拒绝下单，而不是留到结算时才发现没法算钱。
         *
         * ⚠️ 2026-09-23 之前这里是 `resolve(POWERBANK, null, null)` —— **站点压根没传**。
         * 于是「差异化定价」那套按站点/场景取价的规则从未生效过，每一单都落到设备类型默认方案。
         * 配置再怎么改都不影响金额，而且不报任何错。现在按机柜反查出完整的取价上下文。
         */
        /*
         * 反查机柜要**豁免数据范围** —— 这是服务端派生，不是用户在查数据。
         *
         * 下单发生在 C 端会话里，而 dev_cabinet 只登记了 AGENT / SITE 两个维度
         * （DataScopeRegistration）。handler 是 fail-closed：消费者的维度没登记，
         * 这次查询就被拼成 `1=0`，**机柜永远查不到**。
         *
         * 后果有两层，且都不报错：
         *   · 归属三列写不进去 → 代理在自己后台里看不到这一单；
         *   · 取价上下文（站点/场景/区域/品牌）全空 → 那几维不参与匹配，
         *     一律落到设备类型默认方案。目前库里只有一个 ALL/* 方案，
         *     所以还看不出差别 —— 等真配了按站点的价，差别就是钱。
         *
         * 同类方法（detail / returnOrder / buyout）早就是这么写的，rent() 漏了。
         */
        DevCabinet cab = DataScopeContext.executeWithoutScope(() ->
                cabinetMapper.selectOne(new LambdaQueryWrapper<DevCabinet>()
                        .eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1")));
        rentability.checkRent(cab);
        requireNoArrears(cUserNo);
        // 从柜内在库的宝里挑一颗；柜子有宝台账却挑不出能借的 → 拒借（没有台账的柜按旧行为放行）
        ai.neargo.sharehub.dev.port.PowerbankTracker.Pick pick = powerbankTracker.pickForRent(cabinetNo);
        if (pick.tracked() && pick.powerbankNo() == null) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.rent.no_stock");
        }
        if (pick.powerbankNo() != null) {
            e.setPowerbankNo(pick.powerbankNo());
            e.setSlotIndex(pick.slotIndex());
        }

        /*
         * 数据范围锚点：这三列决定**这一单归谁看**（DataScopeRegistration 里
         * ord_order → AGENT:agent_no / SITE:site_no）。不写的话代理在自己后台里
         * 一条都看不到 —— 而页面照常渲染，只是少了行，不报错也不告警。
         *
         * 取自机柜自己的冗余列，与 ScopeAnchorSeeder 的回填口径逐字一致。
         * 机柜查不到就留空：宁可这一单谁都看不见，也不能瞎归一个代理 ——
         * 归错的后果是把别人的营收摆在他面前。
         */
        e.setLocationNo(cab == null ? null : cab.getLocationNo());
        e.setSiteNo(cab == null ? null : cab.getSiteNo());
        e.setAgentNo(cab == null ? null : cab.getAgentNo());

        PriceQuery pq = DataScopeContext.executeWithoutScope(() -> queryOf(cab, e.getStartedAt()));
        PriceResolver.Resolved priced = priceResolver.resolve(pq);
        // 时段倍率按**下单时刻**解析一次并随快照定格（ADR-028 §四：跨时段长单不分段）。
        priced = priced.withMultiplier(multipliers.resolve(pq.regionId(), pq.at()));
        e.setPricePlanNo(priced.planNo());
        e.setPriceSnapshot(priced.toSnapshot());
        e.setCurrency(priced.currency() == null ? CURRENCY : priced.currency());

        /*
         * 用券：**下单时只绑定，不核销** —— 核销在结算时按实际应收做（见 settle 里的
         * consume）。这里做的是可用性校验，传了一张用不了的券要当场拒单。
         *
         * 静默忽略是更省事的写法，也是更坏的写法：用户在确认页选了券、下单成功、
         * 结算时却按原价扣款，中间没有任何一处报错，他只能事后来投诉。
         */
        /*
         * 免单白名单：**下单时定格 free_reason**，与计价快照同一个道理 ——
         * 借的时候说好免单，还的时候因为白名单被撤销而变成收费，用户无法预期。
         * 额度则在结算时按实际减免额扣（见 settle 里的 freeRents.consume）。
         *
         * 在此之前，`setFreeReason` 在全仓库出现 0 次：表、实体、服务、
         * 运营端授予/撤销端点、ChargeChain 的免单分支全都在，唯独没人写这一列。
         * 于是授了免单的 VIP 照样全额付费，而运营端「免费订单」那页永远是空的
         * —— 看起来像「这个月没人用免单」。
         */
        var grant = freeRents.grantFor(cUserNo, e.getCurrency());
        if (grant != null) {
            e.setFreeReason(grant.reason());
            log.info("命中免单白名单 orderNo={} cUserNo={} reason={}", e.getOrderNo(), cUserNo, grant.reason());
        }

        if (couponNo != null && !couponNo.isBlank()) {
            if (coupons.offerOf(cUserNo, couponNo, e.getCurrency()) == null) {
                throw BizException.badRequest("error.rent.coupon_unusable", couponNo);
            }
            e.setCouponNo(couponNo);
        }

        mapper.insert(e);
        // 生命周期要留痕。此前 ord_event_log **只有运营干预在写**（退款/投诉/异常），
        // 正常的「借出→归还→结算」一条都没有 —— 于是 C 端「状态时间线」和运营端事件页
        // 都只看得到干预记录，看不到这单本身是怎么走过来的。
        appendEvent(e.getOrderNo(), null, e.getStatus(), "RENT", e.getCUserNo());
        if (pick.powerbankNo() != null) powerbankTracker.rented(pick.powerbankNo(), e.getOrderNo());

        // 双写扩展表。过渡期主表同名列暂留（删列不可回退），两处必须一致 ——
        // 等所有读路径切到扩展表、验证一个版本周期后再 DROP 主表冗余列。
        OrdRentExt ext = new OrdRentExt();
        ext.setOrderNo(e.getOrderNo());
        ext.setPowerbankNo(e.getPowerbankNo());
        rentExtMapper.insert(ext);

        /*
         * 真的向柜机发一条弹仓指令。此前这里返回的是 `"CMD" + System.nanoTime()` ——
         * 一个现造的字符串，扫码借出从不向柜机发送任何东西。
         *
         * 指令幂等键是 `<orderNo>:EJECT`（见 CabinetCommandPort），同一单重发不会弹两块宝。
         */
        String commandNo = cabinetCommands.eject(cabinetNo, e.getSlotIndex(), e.getOrderNo());
        log.info("借出发起 orderNo={} cabinetNo={} slot={} command={}",
                e.getOrderNo(), cabinetNo, e.getSlotIndex(), commandNo);

        /*
         * 没有真机的环境（演示/测试）：立刻自己补一次回执，走的是与设备回执**同一个方法**。
         * 开关与横幅见 {@link EjectSimulation} —— 接上真驱动那天把它置 false，这里一行不改。
         */
        if (ejectSimulation.enabled()) {
            confirmDelivered(e.getOrderNo(), "SIMULATED");
        }
        return new RentResult(e.getOrderNo(), e.getPowerbankNo(), commandNo);
    }

    @Override
    public OkResult returnOrder(String orderNo, String returnCabinetNo) {
        return returnOrderAt(orderNo, returnCabinetNo, null);
    }

    /**
     * 出宝回执：DISPENSING → IN_USE。
     *
     * <p><b>条件更新 + 幂等</b>：回执可能重复投递（网关重试），也可能与「超时撤单」赛跑。
     * 以 {@code status=DISPENSING} 为更新条件，谁先到谁赢，输的那一边返回 false 而不是抛错 ——
     * 一条迟到的回执不该把已经撤掉的单再拉回使用中。
     *
     * <p>计费起点仍是 {@code rent_start_at}（下单时刻）。出宝通常在数秒内完成，
     * 把起点挪到回执会让「下单到出宝」这段时间不计费，那是另一个口径问题，
     * 要改得和运营侧一起改。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean confirmDelivered(String orderNo, String reason) {
        return Boolean.TRUE.equals(DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = mapper.selectOne(new LambdaQueryWrapper<OrdOrder>()
                    .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
            if (e == null || !OrderStatus.DISPENSING.name().equals(e.getStatus())) return false;
            String before = e.getStatus();
            String to = stateMachine.next(before, "DELIVER");
            int n = mapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrdOrder>()
                            .eq(OrdOrder::getOrderNo, orderNo).eq(OrdOrder::getStatus, before)
                            .set(OrdOrder::getStatus, to));
            if (n == 0) return false;
            appendEvent(orderNo, before, to, "DELIVER", OPERATOR_SYSTEM);
            log.info("出宝回执 orderNo={} {}→{} reason={}", orderNo, before, to, reason);
            return true;
        }));
    }

    /**
     * 设备回执只带柜机（可能带仓位），不带订单号 —— 按柜机/仓位反查正在等出宝的那一单。
     *
     * <p>取**最近的一单**：同一台柜机上同时有两单在等出宝是极少数情况（指令是串行下发的），
     * 真撞上了也只能按时间就近归属；归错的后果是两单互换了回执，而两单都会 IN_USE，
     * 比「都超时撤单」好。
     */
    @Override
    public String pendingDeliveryOrder(String cabinetNo, Integer slotIndex) {
        if (cabinetNo == null || cabinetNo.isBlank()) return null;
        OrdOrder e = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                new LambdaQueryWrapper<OrdOrder>()
                        .eq(OrdOrder::getCabinetNo, cabinetNo)
                        .eq(OrdOrder::getStatus, OrderStatus.DISPENSING.name())
                        // 仓位给了才按它筛：仓位级上报没接入的柜子回执里没有这一项，
                        // 按 null 去比会一单都匹配不到（而那等于回执全丢）
                        .eq(slotIndex != null, OrdOrder::getSlotIndex, slotIndex)
                        .orderByDesc(OrdOrder::getId).last("limit 1")));
        return e == null ? null : e.getOrderNo();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean cancelUndelivered(String orderNo, String reason) {
        return Boolean.TRUE.equals(DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                    new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo).last("limit 1")));
            if (e == null || !OrderStatus.DISPENSING.name().equals(e.getStatus())) return false;
            String before = e.getStatus();
            String to = stateMachine.next(before, "CANCEL");
            int n = DataScopeContext.executeWithoutScope(() -> mapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrdOrder>()
                            .eq(OrdOrder::getOrderNo, orderNo).eq(OrdOrder::getStatus, before)
                            .set(OrdOrder::getStatus, to).set(OrdOrder::getAmount, java.math.BigDecimal.ZERO)
                            .set(OrdOrder::getFeeAmount, 0d)   // free_reason 回指免费白名单，不写自由文本
                            .set(OrdOrder::getEndedAt, java.time.LocalDateTime.now())));
            if (n == 0) return false;
            appendEvent(orderNo, before, to, "CANCEL", OPERATOR_SYSTEM);
            log.info("撤销未出宝订单 orderNo={} reason={}", orderNo, reason);
            // 预授权释放 / 退款经支付接入（pay-svc）确认，接入前这里只撤单不动钱
            return true;
        }));
    }

    @Override
    public OkResult returnOrderAt(String orderNo, String returnCabinetNo, java.time.LocalDateTime endUtc) {
        return settleReturn(orderNo, returnCabinetNo, endUtc, true);
    }

    /**
     * 归还结算本体。{@code trackBank=false} 给运营「强制归还」用：那时并不知道宝在哪个柜子，
     * 不能把宝的位置改成某个柜（更不能改成空）—— 宝的去向由真实的归还信号或资产盘点来定。
     */
    private OkResult settleReturn(String orderNo, String returnCabinetNo, java.time.LocalDateTime endUtc,
                                  boolean trackBank) {
        OrdOrder e = require(orderNo);
        String beforeReturn = e.getStatus();
        e.setStatus(stateMachine.next(e.getStatus(), "RETURN"));   // IN_USE→RETURNED，非法迁移拒
        appendEvent(orderNo, beforeReturn, e.getStatus(), "RETURN", OPERATOR_SYSTEM);
        e.setReturnCabinetNo(returnCabinetNo);
        String endAt = endUtc == null ? nowUtc() : endUtc.toString();   // 同 rent()：DATETIME(3) 不接受带 Z 的字面量
        e.setRentEndAt(endAt);
        long min = durationMinutes(e.getRentStartAt(), endAt);
        e.setDurationMin((int) min);   // **真实时长**，不扣会员免费时长 —— 那是运营数据，不是账
        e.setEndedAt(java.time.LocalDateTime.now());

        /*
         * 会员权益按**归还时刻**解析，不进快照 —— 与倍率（下单时定格）相反，
         * 见 ChargeChain 类注释：「券与会员权益本来就是结算时才确定的」。
         */
        var rights = memberRights.rightsOf(e.getCUserNo());

        // ①② 按**下单时的快照**计价，不回读 price_plan —— 「改价不影响在途单」的落点。
        java.math.BigDecimal gross = priceEngine.price(
                PriceResolver.fromSnapshot(e.getPriceSnapshot()),
                java.util.Map.of("MINUTE", java.math.BigDecimal.valueOf(billingMinutes(min, rights))),
                e.getCurrency()).total();

        /*
         * ③④ 倍率 / 券 / 免单。
         *
         * **倍率从快照读，券与免单按归还时刻算** —— 两者时机不同，别混为一谈：
         * 倍率在下单时就定格了（ADR-028 §四：按开始时刻取值，跨时段长单不分段），
         * 归还时重新解析会让「借的时候是平价、还的时候撞上高峰」变成涨价，用户无法预期；
         * 而券与会员权益本来就是结算时才确定的。
         *
         * 原先这里倍率位写死 null —— 「活动/时段价」菜单能存能改，订单一分钱都不多收。
         */
        java.math.BigDecimal capTotal = PriceResolver.capTotalFromSnapshot(e.getPriceSnapshot());
        java.math.BigDecimal multiplier = PriceResolver.multiplierFromSnapshot(e.getPriceSnapshot());
        ChargeChain.Waiver waiver = waiverOf(e);
        ChargeChain.Member member = memberOf(rights);
        ChargeChain.Charged charged = chargeChain.charge(gross, multiplier, member, couponOf(e), capTotal, waiver);

        /*
         * 免单额度与券同一个套路：**先算后扣，扣不动就按原价重算**。
         * 额度是钱，两单并发用同一份额度时只能有一单扣到。
         */
        if (charged.waivedAmount().signum() > 0
                && !freeRents.consume(e.getCUserNo(), orderNo, charged.waivedAmount())) {
            e.setFreeReason(null);
            waiver = null;
            charged = chargeChain.charge(gross, multiplier, member, couponOf(e), capTotal, null);
        }

        /*
         * 先算后核销，且**核销失败就重算** ——
         *
         * 顺序不能反：门槛达没达要拿「封顶后的应收」判，而那个数只有 charge() 算得出。
         * 先核销再算的话，一张「满 10 减 3」用在 6 块钱的单上会被白白烧掉。
         *
         * 而核销是带条件的原子更新，它的返回值就是并发裁决：两单同时用一张券，
         * 只有一单会拿到 true。抢输的那单必须按无券重算 ——
         * 不重算就是「券被别人用了，钱却从我这里少收」。
         */
        if (charged.couponUsed().signum() > 0 && !coupons.consume(e.getCouponNo(), orderNo)) {
            log.warn("券已被其它订单核销，本单按无券结算 orderNo={} couponNo={}", orderNo, e.getCouponNo());
            e.setCouponNo(null);
            charged = chargeChain.charge(gross, multiplier, member, null, capTotal, waiver);
        }

        e.setAmount(charged.payable());
        // 与 couponNo/couponAmount 同一套路：等级答「凭什么打折」，金额答「折了多少」。
        // 后者事后推不出来 —— 权益配置会改，而折扣额还取决于当时的应收
        e.setMemberLevel(rights == null ? null : rights.level());
        e.setMemberDiscountAmount(charged.memberDiscount());
        e.setCouponAmount(charged.couponUsed());
        e.setWaivedAmount(charged.waivedAmount());
        e.setBuyout(charged.buyout() ? 1 : 0);
        e.setFeeAmount(charged.payable().doubleValue());   // 过渡期双写，待前端与报表切到 amount 后移除
        String beforeSettle = e.getStatus();
        e.setStatus(stateMachine.next(e.getStatus(), "SETTLE"));   // RETURNED→SETTLED；请款见 collect
        mapper.updateById(e);
        appendEvent(orderNo, beforeSettle, e.getStatus(), "SETTLE", OPERATOR_SYSTEM);

        collect(e, "RENT");   // 收到钱才发结算事件（分润），见 #collect
        if (trackBank) powerbankTracker.returned(e.getPowerbankNo(), returnCabinetNo, null);   // 仓位由设备的识别回执补齐
        return new OkResult(true);
    }

    /**
     * 进行中订单的实时试算。**只读**：不核销券（{@code coupons.consume}）、
     * 不扣免单额度（{@code freeRents.consume}）、不写库。
     *
     * <p>计价口径与 {@link #returnOrderAt} 的结算**逐项对齐** —— 同一份下单快照、
     * 同一个倍率、同一条券/免单/封顶链。对不齐的后果不是报错，是用户盯了一路的数
     * 与最终账单不一样，而那件事只有他会发现。
     *
     * <p>并发下券可能被别的单抢走、免单额度可能被扣光：试算按**当下能用**的算，
     * 到结算那一刻若抢输了会按无券重算（那条路已有 warn 日志）。试算本身不打日志，
     * 见 {@link #couponOf(OrdOrder, boolean)}。
     */
    @Override
    public ai.neargo.sharehub.trade.dto.TradeLegacyDtos.FeeEstimate estimateNow(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = mapper.selectOne(new LambdaQueryWrapper<OrdOrder>()
                    .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
            // 只有进行中的单才有「现在欠多少」这件事；已结算的单有实打实的 fee_amount
            if (e == null || !OrderStatus.IN_USE.name().equals(e.getStatus())) return null;
            long min = durationMinutes(e.getRentStartAt(), nowUtc());
            var rights = memberRights.rightsOf(e.getCUserNo());
            java.math.BigDecimal gross = priceEngine.price(
                    PriceResolver.fromSnapshot(e.getPriceSnapshot()),
                    java.util.Map.of("MINUTE", java.math.BigDecimal.valueOf(billingMinutes(min, rights))),
                    e.getCurrency()).total();
            java.math.BigDecimal capTotal = PriceResolver.capTotalFromSnapshot(e.getPriceSnapshot());
            ChargeChain.Charged charged = chargeChain.charge(gross,
                    PriceResolver.multiplierFromSnapshot(e.getPriceSnapshot()),
                    memberOf(rights), couponOf(e, false), capTotal, waiverOf(e, false));
            return new ai.neargo.sharehub.trade.dto.TradeLegacyDtos.FeeEstimate(
                    (int) min, charged.payable(), charged.couponUsed(), charged.waivedAmount(),
                    charged.buyout(), e.getCurrency());
        });
    }

    /**
     * 逾期达封顶自动买断（执行清单 A1 · 联动 E17）。按当前时长试算：触及方案总封顶（买断价）即以封顶价买断结单，
     * 宝 → SOLD，照常发结算事件走分润。未触顶 / 快照没有总封顶 / 已不在进行中 → 返回 false。
     */
    @Override
    public boolean buyoutIfCapped(String orderNo) {
        return Boolean.TRUE.equals(DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = mapper.selectOne(new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
            if (e == null || !OrderStatus.IN_USE.name().equals(e.getStatus())) return false;
            java.math.BigDecimal cap = PriceResolver.capTotalFromSnapshot(e.getPriceSnapshot());
            if (cap == null) return false;
            long min = durationMinutes(e.getRentStartAt(), nowUtc());
            /*
             * 免费时长照扣（它改的是计费分钟数），但**买断判定不传会员折扣** ——
             * 总封顶就是买断价，用折后金额判触顶会让会员逾期更久才买断（见 ChargeChain 约束 3）。
             */
            java.math.BigDecimal gross = priceEngine.price(PriceResolver.fromSnapshot(e.getPriceSnapshot()),
                    java.util.Map.of("MINUTE", java.math.BigDecimal.valueOf(
                            billingMinutes(min, memberRights.rightsOf(e.getCUserNo())))),
                    e.getCurrency()).total();
            ChargeChain.Charged charged = chargeChain.charge(gross, PriceResolver.multiplierFromSnapshot(e.getPriceSnapshot()),
                    null, cap, null);
            if (!charged.buyout()) return false;
            buyout(orderNo, cap);   // 买断也是收入：请款与分润在 buyout 里（collect）
            log.info("逾期达封顶自动买断 orderNo={} minutes={} cap={}", orderNo, min, cap);
            return true;
        }));
    }

    /**
     * 按机柜反查取价上下文（修「下单没传站点」）。
     *
     * <p>机柜自己就带 {@code locationNo / siteNo / agentNo / vendorCode / model} —— 这几层不必跨服务。
     * 只有场地方 / 场景 / 区域在 platform，走 {@link SiteQueryPort} 取。
     *
     * <p><b>取不到站点不静默降级</b>：对应层的引用留 {@code null}，那几层就不参与匹配，
     * 最终落到 {@code ALL} 层（默认方案）并在快照里如实记 {@code level=ALL}。
     * 不把缺失当成通配 —— 通配会命中一个本不该命中的方案，那正是这次要修的那类错。
     */
    /** 收已经查出来的机柜 —— 锚点与取价共用同一次查询，也保证两者看到的是同一行。 */
    private PriceQuery queryOf(DevCabinet c, java.time.LocalDateTime at) {
        if (c == null) {
            // 机柜查不到不在这里拦（弹仓那一步自然会失败），但取价要如实反映「什么都不知道」
            return PriceQuery.ofDeviceType(DEVICE_TYPE_POWERBANK, at);
        }
        String venueNo = null, sceneType = null, regionId = null, brandNo = null;
        SiteQueryPort port = siteQuery.getIfAvailable();
        if (port != null && c.getSiteNo() != null && !c.getSiteNo().isBlank()) {
            SiteBrief b = port.briefsByNos(java.util.List.of(c.getSiteNo()))
                    .stream().findFirst().orElse(null);
            if (b != null) {
                venueNo = b.venueNo();
                sceneType = b.sceneType();
                regionId = b.regionId();
                brandNo = b.brandNo();
            }
        }
        return new PriceQuery(
                c.getDeviceType() == null ? DEVICE_TYPE_POWERBANK : c.getDeviceType(),
                c.getCabinetNo(), c.getLocationNo(), c.getSiteNo(),
                venueNo, c.getAgentNo(), sceneType, regionId,
                // 品牌取自站点（一站一品牌，B1 已落地）；站点查不到时留 null，
                // 那一维就不参与匹配 —— 与其它层一样，缺失不当成通配。
                c.getVendorCode(), c.getModel(), brandNo, at);
    }

    /**
     * 结算即请款（C-BL-04，2026-09-27 拍板：先收钱再分账）。
     *
     * <p>此前结算从不请款：算完钱就 SETTLE 并发结算事件，finance 按「应收」生成分润 —— 那笔钱从没收过，
     * 「欠费」在数据上也不成立。现在：
     * <ul>
     *   <li>应付为 0（免单 / 全额券抵）→ 已收，照常发事件（基数 0，finance 自己跳过）；</li>
     *   <li>否则经支付通道请款：成功 → 已收并<b>此时才</b>发 {@link OrderSettledEvent}；
     *       失败或抛异常 → 欠费（UNPAID），<b>结算不回滚</b> —— 柜子已还、钱已算，扣款失败不能让订单回到进行中。</li>
     * </ul>
     * 不新增事件：{@code OrderSettledEvent} 的基数注释本来就是「本单<b>实收</b>」，挪到收款之后才与它自己的定义一致。
     *
     * <p>资金来源一期统一走通道（桩恒成功；接 nearpay 后由通道扣卡 / 扣预授权）。
     * 押金、预授权、钱包三个来源的前提都还不存在，见 TDD-欠费补缴与结算请款.md §6。
     */
    private void collect(OrdOrder e, String payType) {
        java.math.BigDecimal due = e.getAmount() == null ? java.math.BigDecimal.ZERO : e.getAmount();
        if (due.signum() <= 0) {
            markPaid(e, java.math.BigDecimal.ZERO, null);
            return;
        }
        ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry paid = null;
        try {
            paid = payments.pay(e.getOrderNo(), e.getCUserNo(), payType, due, e.getCurrency(), null,
                    "SETTLE-" + e.getOrderNo());
        } catch (RuntimeException ex) {
            log.warn("结算请款异常，已记欠费 orderNo={} amount={} —— 用户可在订单详情补缴，运营可催缴",
                    e.getOrderNo(), due, ex);
        }
        if (paid != null && ai.neargo.sharehub.trade.pay.PayOrderStatus.PAID.name().equals(paid.status())) {
            markPaid(e, due, paid.payNo());
            return;
        }
        e.setPayStatus(PAY_UNPAID);
        mapper.updateById(e);
        notifyArrears(e, due);
        log.warn("结算请款未成功，已记欠费 orderNo={} amount={} status={} —— 用户可在订单详情补缴，运营可催缴",
                e.getOrderNo(), due, paid == null ? "ERROR" : paid.status());
    }

    /**
     * 欠费告知：发 {@link ai.neargo.sharehub.api.core.event.OrderArrearsEvent}，由 user 域发站内信。
     * 不直接调站内信服务 —— user 已依赖 trade，再反向依赖就成环（ArchitectureTest#noCyclesBetweenDomains）。
     */
    private void notifyArrears(OrdOrder e, java.math.BigDecimal due) {
        eventBus.publish(new ai.neargo.sharehub.api.core.event.OrderArrearsEvent(
                e.getOrderNo(), e.getCUserNo(), due, e.getCurrency()));
    }

    private void markPaid(OrdOrder e, java.math.BigDecimal amount, String payNo) {
        e.setPayStatus(PAY_PAID);
        e.setPaidAmount(amount);
        e.setPaidAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));   // 同 rentStartAt，UTC
        e.setPayNo(payNo);
        mapper.updateById(e);
        publishSettled(e);
    }

    private static final String PAY_PAID = "PAID";
    private static final String PAY_UNPAID = "UNPAID";

    /**
     * 补缴（{@code POST /mp/trade/pay}）。只接受<b>已结算且欠费</b>的单 —— 此前这个口不看订单状态、
     * 也不幂等，对同一张单调几次就收几次钱。金额取订单应付，不认请求体。
     * 成功 → 已收并发结算事件：迟到的分润照常归结算那个月（period 按 rentEndAt 定格）。
     */
    @Override
    public ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry repay(String orderNo, String payType) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdOrder e = require(orderNo);
            if (PAY_PAID.equals(e.getPayStatus())) {
                throw BizException.conflict("error.pay.already_paid", orderNo);
            }
            if (!PAY_UNPAID.equals(e.getPayStatus())) {
                throw BizException.conflict("error.pay.not_payable", e.getStatus());
            }
            ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry paid = payments.pay(orderNo, e.getCUserNo(),
                    payType, e.getAmount(), e.getCurrency(), null, "REPAY-" + orderNo + "-" + System.currentTimeMillis());
            if (ai.neargo.sharehub.trade.pay.PayOrderStatus.PAID.name().equals(paid.status())) {
                markPaid(e, e.getAmount(), paid.payNo());
                log.info("补缴成功 orderNo={} amount={}", orderNo, e.getAmount());
            }
            return paid;
        });
    }

    /**
     * 欠费拦下次借出（C-BL-04 规则）。放这里而不是 {@code RentabilityGuard}：
     * 那是<b>设备</b>能不能借（也给列表显示「可借」用），欠费是<b>人</b>能不能借。
     */
    @Override
    public void requireNoArrears(String cUserNo) {
        OrdOrder owed = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(new LambdaQueryWrapper<OrdOrder>()
                .eq(OrdOrder::getCUserNo, cUserNo)
                .eq(OrdOrder::getPayStatus, PAY_UNPAID)
                .orderByAsc(OrdOrder::getId).last("limit 1")));
        if (owed != null) {
            // 带上单号：端上据此直接打开那张欠费单去补缴，而不是让用户自己去订单列表里找
            // 金额按两位小数传：交给 MessageFormat 的是数字的话，18.00 会被念成「18」（真机弹窗实测）
            String amt = (owed.getAmount() == null ? java.math.BigDecimal.ZERO : owed.getAmount())
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            throw BizException.conflict("error.rent.arrears", amt, owed.getOrderNo())
                    .withData(java.util.Map.of("orderNo", owed.getOrderNo(),
                            "amount", owed.getAmount() == null ? java.math.BigDecimal.ZERO : owed.getAmount()));
        }
    }

    /**
     * 结算完成 → 发「订单已结算」，finance 据此生成分润明细。
     *
     * <p><b>为什么在这里发</b>：这是订单第一次、也是唯一一次拿到最终金额的位置。
     * 在此之前发，基数是错的；不发，订单的收入就永远不会变成任何人的分成 ——
     * 在补上这一段之前，`share_record` 只有演示数据在写，结算单永远是空的。
     *
     * <p><b>基数取实收 {@code amount} 而不是 {@code gross}</b>：免单与券抵扣的部分
     * 没有真实现金流，按应收分账等于用平台的钱替用户给场地方付分成。
     *
     * <p><b>站点/代理由这里解析</b>：`dev_cabinet` 上有归属冗余列（V9 的数据范围锚点），
     * core 自己就持有。让 finance 回查 core 等于把同步调用藏在事件里
     * （见 {@code DomainEvent} 的约定）。
     *
     * <p>归属链断了（机柜没绑点位/站点）时仍然发事件、由消费方告警并跳过 ——
     * <b>不在这里静默不发</b>：那样「这笔钱本该分给谁」会连一行日志都不留。
     */
    private void publishSettled(OrdOrder e) {
        DevCabinet cab = e.getCabinetNo() == null ? null : cabinetMapper.selectOne(
                new LambdaQueryWrapper<DevCabinet>()
                        .eq(DevCabinet::getCabinetNo, e.getCabinetNo()).last("limit 1"));
        /*
         * —— 归属周期：按**结算时刻**定格，且折算到**业务日历** ——
         *
         * 这一行此前是 `LocalDate.now().toString().substring(0, 7)`，与它上面那句注释
         * （「按结算时刻定格，不用『今天』」）**正好相反**，而且还错了第二层：
         *
         * ① 用「今天」：跨月补算/重放时会把钱记进补算那个月，而不是原本该归的月；
         * ② 用服务器时区的「今天」：生产跑在 Asia/Shanghai，业务在 Asia/Dubai ——
         *    迪拜 9-30 20:00 之后结算的单，period 直接变成 `2026-10`。
         *    每月有 4 小时的单会记错月份，而结算单上看不出任何异常。
         *
         * 取 rentEndAt 而不是 endedAt：两条结算路径（归还 / 逾期买断）都会写 rentEndAt，
         * 而 endedAt 只有归还那条写；更要紧的是 **rentEndAt 存的是 UTC**，
         * 折算到业务时区是精确的，不像 endedAt 那样得先假设「它是哪个时区的」。
         */
        java.time.LocalDate settledOn = e.getRentEndAt() == null ? clock.today()
                : parseTs(e.getRentEndAt()).atZone(clock.zone()).toLocalDate();
        String period = settledOn.toString().substring(0, 7);
        eventBus.publish(new OrderSettledEvent(
                e.getOrderNo(), e.getCabinetNo(),
                cab == null ? null : cab.getSiteNo(),
                cab == null ? null : cab.getAgentNo(),
                e.getAmount(), e.getCurrency(), period, settledOn.toString()));
    }

    /**
     * 订单上挂的券的抵扣规格；无券或券已失效返回 null。
     *
     * <p>返回**规格而不是金额**：门槛与折扣率都要按「封顶后的应收」算，
     * 那个基数只有 {@link ChargeChain#charge} 知道（见 {@link ChargeChain.Coupon#offAgainst}）。
     *
     * <p>这里**重新校验一次可用性**，尽管下单时已经校验过：借出到归还之间可能过了有效期，
     * 也可能被别处用掉。过期的券静静地抵扣下去，账面上谁也看不出不对。
     */
    /**
     * 订单上定格的免单原因 → 带额度上限的免单授权；正常单返回 null。
     *
     * <p>上限**按结算时刻的剩余额度取**：下单时只定格「免不免」，
     * 中间可能有别的单把 AMOUNT 额度吃掉了一部分。额度没了就按原价 ——
     * 这时 {@code grantFor} 返回 null，本方法也返回 null。
     */
    private ChargeChain.Waiver waiverOf(OrdOrder e) {
        return waiverOf(e, true);
    }

    /**
     * @param warn 结算时为 true；**试算时必须传 false** —— 试算每 15 秒来一次，
     *             按结算的口径打 WARN 会把同一句话刷满日志，而真告警就淹在里面了
     */
    private ChargeChain.Waiver waiverOf(OrdOrder e, boolean warn) {
        if (e.getFreeReason() == null || e.getFreeReason().isBlank()) return null;
        var grant = freeRents.grantFor(e.getCUserNo(), e.getCurrency());
        if (grant == null) {
            if (warn) log.warn("结算时免单额度已不可用，本单按原价 orderNo={} reason={}", e.getOrderNo(), e.getFreeReason());
            return null;
        }
        // reason 用订单上定格的那个，不用白名单当前的 —— 白名单改了用途不该改写历史订单
        return new ChargeChain.Waiver(e.getFreeReason(), grant.capAmount());
    }

    private ChargeChain.Coupon couponOf(OrdOrder e) {
        return couponOf(e, true);
    }

    /** @param warn 见 {@link #waiverOf(OrdOrder, boolean)}。 */
    private ChargeChain.Coupon couponOf(OrdOrder e, boolean warn) {
        if (e.getCouponNo() == null || e.getCouponNo().isBlank()) return null;
        var offer = coupons.offerOf(e.getCUserNo(), e.getCouponNo(), e.getCurrency());
        if (offer == null) {
            if (warn) log.warn("结算时券已不可用，本单按无券计价 orderNo={} couponNo={}", e.getOrderNo(), e.getCouponNo());
            return null;
        }
        return new ChargeChain.Coupon(offer.type(), offer.value(), offer.threshold());
    }

    @Override
    public InterventionDtos.OrderInterveneResult intervene(String orderNo, InterventionDtos.InterveneReq req) {
        if (req == null || req.action() == null) throw new IllegalArgumentException("缺少干预动作 action");
        InterventionDtos.Rule rule = InterventionDtos.RULES.get(req.action());
        if (rule == null)
            throw new IllegalArgumentException("非法干预动作: " + req.action() + "，允许: " + InterventionDtos.ACTIONS);
        if (req.reason() == null || req.reason().isBlank()) throw new IllegalArgumentException("干预原因必填");
        if ("compensate".equals(req.action()) && req.amount() == null)
            throw new IllegalArgumentException("补偿必须带金额 amount");

        OrdOrder e = require(orderNo);
        String before = e.getStatus();
        if (!rule.from().contains(OrderStatus.of(before)))
            throw new IllegalArgumentException("状态 " + before + " 不允许 " + req.action() + "，允许自: " + rule.from());
        String after = rule.to() == null ? before : rule.to().name();
        if ("force_return".equals(req.action())) {
            e = forceReturn(e);   // 走正常结算（计费 / 请款 / 分润），不再直接改状态
            after = e.getStatus();
        } else if (rule.to() != null) {
            e.setStatus(after);
            mapper.updateById(e);
        }

        OrdIntervention log = new OrdIntervention();
        log.setInterventionNo(IdGenerator.next(BizKey.ORDER_INTERVENTION));
        log.setTenantId(e.getTenantId());
        log.setOrderNo(orderNo);
        log.setAction(req.action());
        log.setOperator(operatorOf(req));
        log.setReason(req.reason());
        log.setAmount(req.amount());
        log.setCurrency(e.getCurrency());
        log.setBeforeStatus(before);
        log.setAfterStatus(after);
        log.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        interventionMapper.insert(log);
        return new InterventionDtos.OrderInterveneResult(toVO(e), toInterventionVO(log));
    }

    /**
     * 运营「强制归还」（2026-09-28 拍板：走正常结算）。
     *
     * <p>此前直接把订单写成 SETTLED：不计费、不请款、不发结算事件 —— 用了三小时账上是 0，
     * 场地方分不到钱，订单也不在收款态里（既非已收也非欠费）。要免单另走「减免」（waive）。
     * <ul>
     *   <li>使用中：与正常归还同一条路（按实际时长计费 → 请款 → 分润），只是不改宝的位置；</li>
     *   <li>出宝中 / 异常：宝没交到用户手上，0 元结清，照样经收款步骤落「已收」。</li>
     * </ul>
     */
    private OrdOrder forceReturn(OrdOrder e) {
        if (OrderStatus.IN_USE.name().equals(e.getStatus())) {
            settleReturn(e.getOrderNo(), null, null, false);
        } else {
            String before = e.getStatus();
            e.setRentEndAt(nowUtc());
            e.setAmount(java.math.BigDecimal.ZERO);
            e.setFeeAmount(0.0);
            e.setStatus(OrderStatus.SETTLED.name());   // 出宝中 → 已结算不在正常状态机里，干预本就是绕开正常流程
            mapper.updateById(e);
            appendEvent(e.getOrderNo(), before, e.getStatus(), "SETTLE", OPERATOR_SYSTEM);
            collect(e, "RENT");
        }
        return require(e.getOrderNo());
    }

    @Override
    public PageResult<InterventionDtos.OrderIntervention> interventions(Integer page, Integer size,
                                                                        String orderNo, String action) {
        LambdaQueryWrapper<OrdIntervention> w = new LambdaQueryWrapper<>();
        w.eq(orderNo != null && !orderNo.isBlank(), OrdIntervention::getOrderNo, orderNo);
        w.eq(action != null && !action.isBlank(), OrdIntervention::getAction, action);
        w.orderByDesc(OrdIntervention::getId);
        Page<OrdIntervention> r = interventionMapper.selectPage(new Page<>(pg(page), sz(size)), w);
        return new PageResult<>(r.getRecords().stream().map(RentOrderServiceImpl::toInterventionVO).toList(),
                r.getTotal());
    }

    @Override
    public java.util.Map<String, Long> countByUsers(java.util.Collection<String> cUserNos) {
        if (cUserNos == null || cUserNos.isEmpty()) return java.util.Map.of();
        var w = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OrdOrder>()
                .select("c_user_no", "COUNT(*) AS cnt")
                .in("c_user_no", cUserNos)
                .groupBy("c_user_no");
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : mapper.selectMaps(w)) {
            out.put(String.valueOf(row.get("c_user_no")), ((Number) row.get("cnt")).longValue());
        }
        return out;
    }

    /** 操作人：入参显式指定优先，否则取当前运营端会话；两者皆无（如内部调用）记 system。 */
    private static String operatorOf(InterventionDtos.InterveneReq req) {
        if (req.operatorName() != null && !req.operatorName().isBlank()) return req.operatorName();
        try {
            return StaffContext.require().username();
        } catch (RuntimeException noSession) {
            return "system";
        }
    }

    private static InterventionDtos.OrderIntervention toInterventionVO(OrdIntervention e) {
        return new InterventionDtos.OrderIntervention(e.getInterventionNo(), e.getOrderNo(), e.getAction(),
                e.getOperator(), e.getReason(), e.getAmount(), e.getCurrency(),
                e.getBeforeStatus(), e.getAfterStatus(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    /**
     * 租借时长（分钟）。**计费的唯一输入，故解析失败必须炸而不是返回 0。**
     *
     * <p>原实现是 {@code Instant.parse} + {@code catch → return 0}：两个问题叠在一起。
     * {@code Instant.parse} 只认带 {@code Z} 的字面量，而时间列是 {@code DATETIME(3)}、
     * 存的是无 {@code Z} 的本地格式（见 {@link #nowUtc()}），于是解析必然抛异常、被 catch 吞掉、
     * 时长变 0 → {@link #fee} 算出 0 → **每一单都免费，而且没有任何报错**。
     * 静默少收钱比报错难查得多，所以这里两种格式都接受，真解析不出来就抛。
     */
    private static long durationMinutes(String startIso, String endIso) {
        return Math.max(0, Duration.between(parseTs(startIso), parseTs(endIso)).toMinutes());
    }

    /**
     * 兼容三种格式的时间串。
     *
     * <ol>
     *   <li>{@code 2026-09-23T10:41:09.501} —— 写入时的格式（{@link #nowUtc()}）</li>
     *   <li>{@code 2026-09-23 10:41:09.501} —— **从库里读回来的格式**。列是 {@code DATETIME(3)}、
     *       而实体字段是 String，JDBC 取回时给的是空格分隔，没有 {@code T}</li>
     *   <li>{@code …Z} —— 旧数据 / 其它来源</li>
     * </ol>
     *
     * <p>第 2 种是 2026-09-23 才发现的：<b>归还订单在任何干净库上必然 500</b>。
     * 下单与归还是两个请求，归还时订单是从库里重新读出来的 —— 于是拿到的是空格格式，
     * 而这里只认 {@code T} 和 {@code Z}，直接抛 DateTimeParseException。
     * 开发库上一直没暴露，是因为那边的数据与连接会话的历史状态掩盖了它；
     * 另一个会话把测试切到「按迁移从零建的干净库」之后，第一次照出来。
     * <b>与「实体有字段、迁移没建列」是同一类：开发库的历史状态让真缺陷在本地永远复现不出来。</b>
     */
    private static Instant parseTs(String ts) {
        if (ts == null || ts.isBlank()) throw new IllegalStateException("租借时间为空，无法计费");
        String v = ts.trim();
        // 空格分隔 → 补回 T，再走 ISO 解析。只替换第一个空格，避免动到带时区的写法。
        if (v.length() > 10 && v.charAt(10) == ' ') v = v.substring(0, 10) + "T" + v.substring(11);
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return Instant.parse(v);   // 带 Z 的旧值；仍解析不了则由本异常向上抛，不静默计 0
        }
    }

    private OrdOrder require(String orderNo) {
        OrdOrder e = mapper.selectOne(new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo));
        if (e == null) throw BizException.notFound(orderNo);
        return e;
    }

    private static int pg(Integer page) { return (page == null || page < 1) ? 1 : page; }
    private static int sz(Integer size) { return (size == null || size < 1) ? 10 : size; }

    private static RentOrder toVO(OrdOrder e) {
        return new RentOrder(e.getOrderNo(), e.getCUserNo(), e.getCabinetNo(), e.getReturnCabinetNo(),
                e.getPowerbankNo(), e.getLocationName(), e.getStatus(), e.getRentStartAt(), e.getRentEndAt(),
                e.getDurationMin(), e.getFeeAmount() == null ? 0 : e.getFeeAmount(),
                e.getDepositAmount() == null ? 0 : e.getDepositAmount(), e.getCurrency(),
                null, null, null, null, null, null, e.getPayStatus());
    }

    /** 统一时间戳：ISO-8601 本地日期时间（UTC 时钟，**无 Z 后缀**）——
     *  ord_order 的时间列是 DATETIME(3)，带时区标记的字面量会被 MariaDB 拒收。 */
    /**
     * 追加订单流水。
     *
     * <p><b>吞掉异常并只记 WARN</b>：留痕失败不该把借出/归还本身带下去 ——
     * 用户手里的充电宝已经弹出来了，这时候因为少一条流水而回滚订单，糟得多。
     * 但日志必须带业务键，否则「少了一条流水」事后无从复现（见 CLAUDE.md 日志规范）。
     *
     * @param operator 谁做的。借出/买断是消费者本人（{@code cUserNo}），
     *                 归还/结算由设备事件与系统驱动，记 {@code SYSTEM} ——
     *                 这里**不取当前登录人**：归还走的是内部端点，
     *                 那个「登录人」是网关的服务身份，写进去会让人以为是客服帮他还的。
     */
    private void appendEvent(String orderNo, String from, String to, String event, String operator) {
        try {
            eventLog.append(orderNo, from, to, event, operator);
        } catch (RuntimeException ex) {
            log.warn("订单流水写入失败，主流程继续 orderNo={} event={} {}→{}", orderNo, event, from, to, ex);
        }
    }

    private static String nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).toString();
    }

    /**
     * 计费分钟数 = 真实时长 − 会员免费时长，下限 0。
     *
     * <p><b>只影响计价输入，不影响 {@code duration_min}</b> ——
     * 把免费时长从时长里抹掉，运营看到的「平均借用时长」就永久偏短，而没有任何地方会报错。
     */
    private static long billingMinutes(long minutes, ai.neargo.sharehub.api.core.port.MemberRightsPort.Rights r) {
        if (r == null || r.freeMinutes() <= 0) return minutes;
        return Math.max(0L, minutes - r.freeMinutes());
    }

    /** 生效权益 → 取价链的会员折扣规格；无折扣时返回 null（不在账单上占一行）。 */
    private static ChargeChain.Member memberOf(ai.neargo.sharehub.api.core.port.MemberRightsPort.Rights r) {
        if (r == null || r.rentDiscount() == null
                || r.rentDiscount().compareTo(java.math.BigDecimal.ONE) >= 0) return null;
        return new ChargeChain.Member(r.rentDiscount());
    }
}
