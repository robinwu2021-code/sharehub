package ai.neargo.sharehub.trade.service.impl;

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
import ai.neargo.sharehub.trade.entity.OrdIntervention;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.InterventionMapper;
import ai.neargo.sharehub.trade.order.entity.OrdRentExt;
import ai.neargo.sharehub.trade.order.mapper.OrdRentExtMapper;
import ai.neargo.sharehub.trade.price.engine.PriceEngine;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import ai.neargo.sharehub.trade.price.engine.ChargeChain;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
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

    private final OrdMapper mapper;
    private final OrdStateMachine stateMachine;
    private final OrdRentExtMapper rentExtMapper;
    private final InterventionMapper interventionMapper;
    private final PriceResolver priceResolver;
    private final PriceEngine priceEngine;
    private final ChargeChain chargeChain;
    private final CabinetMapper cabinetMapper;
    private final DomainEventBus eventBus;

    public RentOrderServiceImpl(OrdMapper mapper, OrdStateMachine stateMachine,
                                OrdRentExtMapper rentExtMapper, InterventionMapper interventionMapper,
                                PriceResolver priceResolver, PriceEngine priceEngine,
                                ChargeChain chargeChain, CabinetMapper cabinetMapper,
                                DomainEventBus eventBus) {
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
        int p = pg(page), s = sz(size);
        LambdaQueryWrapper<OrdOrder> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(OrdOrder::getOrderNo, keyword).or().like(OrdOrder::getCUserNo, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(OrdOrder::getStatus, status);
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
                    lastEject.get(e.getOrderNo()));
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
                throw new IllegalArgumentException("仅使用中的订单可买断，当前状态: " + e.getStatus());
            }
            java.math.BigDecimal price = buyoutPrice == null ? java.math.BigDecimal.ZERO : buyoutPrice;
            e.setRentEndAt(nowUtc());
            e.setAmount(price);
            e.setFeeAmount(price.doubleValue());   // 过渡期双写，同 returnOrder
            e.setBuyout(1);
            // 买断即终局：RETURN→SETTLE 两跳走状态机，保持非法迁移仍会被拒
            e.setStatus(stateMachine.next(e.getStatus(), "RETURN"));
            e.setStatus(stateMachine.next(e.getStatus(), "SETTLE"));
            mapper.updateById(e);
            return toVO(e);
        });
    }

    /** 本服务只处理充电宝单；充电桩/储物柜由各自的 device 子包处理（ADR-018）。 */
    private static final String DEVICE_TYPE_POWERBANK = "POWERBANK";

    @Override
    public RentResult rent(String cUserNo, String cabinetNo) {
        if (cabinetNo == null || cabinetNo.isBlank()) throw new IllegalArgumentException("柜机号为空");
        OrdOrder e = new OrdOrder();
        e.setOrderNo(IdGenerator.next("ORD"));
        e.setCUserNo(cUserNo);
        e.setCabinetNo(cabinetNo);
        e.setPowerbankNo(IdGenerator.next("PB"));
        e.setStatus("IN_USE");                       // 免押预授权 + 弹仓为骨架，直接置借用中
        // ⚠️ 必须是 LocalDateTime 而非 Instant：ord_order.rent_start_at 是 DATETIME(3)，
        // MariaDB 不接受 Instant.toString() 产出的带 Z 字面量，写入直接
        // Data truncation: Incorrect datetime value → 500，即扫码借出**从未成功落库**。
        // 与 OrderSupport.now() 同一处理：只去掉 DB 不认的 Z，时钟仍取 UTC。
        e.setRentStartAt(nowUtc());
        e.setDepositAmount(DEPOSIT);
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

        // 快照计价规格：**展开后的结构**而非 planNo 引用 —— 改价不影响在途单。
        // 匹配不到方案会在这里抛异常拒绝下单，而不是留到结算时才发现没法算钱。
        PriceResolver.Resolved priced = priceResolver.resolve(DEVICE_TYPE_POWERBANK, null, null);
        e.setPricePlanNo(priced.planNo());
        e.setPriceSnapshot(priced.toSnapshot());
        e.setCurrency(priced.currency() == null ? CURRENCY : priced.currency());

        mapper.insert(e);

        // 双写扩展表。过渡期主表同名列暂留（删列不可回退），两处必须一致 ——
        // 等所有读路径切到扩展表、验证一个版本周期后再 DROP 主表冗余列。
        OrdRentExt ext = new OrdRentExt();
        ext.setOrderNo(e.getOrderNo());
        ext.setPowerbankNo(e.getPowerbankNo());
        rentExtMapper.insert(ext);

        return new RentResult(e.getOrderNo(), e.getPowerbankNo(), "CMD" + System.nanoTime());
    }

    @Override
    public OkResult returnOrder(String orderNo, String returnCabinetNo) {
        OrdOrder e = require(orderNo);
        e.setStatus(stateMachine.next(e.getStatus(), "RETURN"));   // IN_USE→RETURNED，非法迁移拒
        e.setReturnCabinetNo(returnCabinetNo);
        String endAt = nowUtc();   // 同 rent()：DATETIME(3) 不接受带 Z 的字面量
        e.setRentEndAt(endAt);
        long min = durationMinutes(e.getRentStartAt(), endAt);
        e.setDurationMin((int) min);
        e.setEndedAt(java.time.LocalDateTime.now());

        // ①② 按**下单时的快照**计价，不回读 price_plan —— 「改价不影响在途单」的落点。
        java.math.BigDecimal gross = priceEngine.price(
                PriceResolver.fromSnapshot(e.getPriceSnapshot()),
                java.util.Map.of("MINUTE", java.math.BigDecimal.valueOf(min)),
                e.getCurrency()).total();

        // ③④ 分时倍率 / 券 / 免单 —— **按归还时刻重算，不快照**：
        // 时段价本就依赖使用时段，券与会员权益在结算时才确定（[TDD §1.1]）。
        ChargeChain.Charged charged = chargeChain.charge(
                gross, null, couponOffOf(e), null, e.getFreeReason());

        e.setAmount(charged.payable());
        e.setWaivedAmount(charged.waivedAmount());
        e.setBuyout(charged.buyout() ? 1 : 0);
        e.setFeeAmount(charged.payable().doubleValue());   // 过渡期双写，待前端与报表切到 amount 后移除
        e.setStatus(stateMachine.next(e.getStatus(), "SETTLE"));   // RETURNED→SETTLED（支付为骨架）
        mapper.updateById(e);

        publishSettled(e);
        return new OkResult(true);
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
        // 归属周期按**结算时刻**定格，不用「今天」—— 跨月补算时用今天会把钱记进错的月份
        String period = java.time.LocalDate.now().toString().substring(0, 7);
        eventBus.publish(new OrderSettledEvent(
                e.getOrderNo(), e.getCabinetNo(),
                cab == null ? null : cab.getSiteNo(),
                cab == null ? null : cab.getAgentNo(),
                e.getAmount(), e.getCurrency(), period));
    }

    /**
     * 订单上挂的券的抵扣额。
     *
     * <p>当前返回 null（无券）—— 券的发放与核销尚未接线（`usr_coupon` 有表有实体，
     * 但没有「下单时选券」的入口）。**此处显式留空并注明，而不是悄悄不调 ChargeChain** ——
     * 后者会让「券抵扣没实现」这件事从代码里看不出来。
     */
    private java.math.BigDecimal couponOffOf(OrdOrder e) {
        return null;
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
        if (!rule.from().contains(before))
            throw new IllegalArgumentException("状态 " + before + " 不允许 " + req.action() + "，允许自: " + rule.from());
        String after = rule.to() == null ? before : rule.to();
        if (rule.to() != null) {
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

    /** 兼容两种历史格式：无 Z（当前写入格式）与带 Z（旧数据/其它来源）。 */
    private static Instant parseTs(String ts) {
        if (ts == null || ts.isBlank()) throw new IllegalStateException("租借时间为空，无法计费");
        try {
            return LocalDateTime.parse(ts).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return Instant.parse(ts);   // 带 Z 的旧值；仍解析不了则由本异常向上抛，不静默计 0
        }
    }

    private OrdOrder require(String orderNo) {
        OrdOrder e = mapper.selectOne(new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo));
        if (e == null) throw new IllegalArgumentException("订单不存在: " + orderNo);
        return e;
    }

    private static int pg(Integer page) { return (page == null || page < 1) ? 1 : page; }
    private static int sz(Integer size) { return (size == null || size < 1) ? 10 : size; }

    private static RentOrder toVO(OrdOrder e) {
        return new RentOrder(e.getOrderNo(), e.getCUserNo(), e.getCabinetNo(), e.getReturnCabinetNo(),
                e.getPowerbankNo(), e.getLocationName(), e.getStatus(), e.getRentStartAt(), e.getRentEndAt(),
                e.getDurationMin(), e.getFeeAmount() == null ? 0 : e.getFeeAmount(),
                e.getDepositAmount() == null ? 0 : e.getDepositAmount(), e.getCurrency());
    }

    /** 统一时间戳：ISO-8601 本地日期时间（UTC 时钟，**无 Z 后缀**）——
     *  ord_order 的时间列是 DATETIME(3)，带时区标记的字面量会被 MariaDB 拒收。 */
    private static String nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).toString();
    }
}
