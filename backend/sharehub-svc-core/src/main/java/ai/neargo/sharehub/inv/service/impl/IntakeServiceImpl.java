package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcRecord;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.dev.service.QcService;
import ai.neargo.sharehub.inv.IntakeStatus;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.IntakeDtos.CreateReq;
import ai.neargo.sharehub.inv.dto.IntakeDtos.DisposeReq;
import ai.neargo.sharehub.inv.dto.IntakeDtos.Intake;
import ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail;
import ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeItem;
import ai.neargo.sharehub.inv.dto.IntakeDtos.ItemRef;
import ai.neargo.sharehub.inv.dto.IntakeDtos.ItemsReq;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.entity.InvIntake;
import ai.neargo.sharehub.inv.entity.InvIntakeItem;
import ai.neargo.sharehub.inv.mapper.InvIntakeItemMapper;
import ai.neargo.sharehub.inv.mapper.InvIntakeMapper;
import ai.neargo.sharehub.inv.service.IntakeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 到仓待检（V192）。
 *
 * <p>独立进销存上线前（本地模式）：「入库」= 设备的所在仓写成本单的仓，质检记录号即放行凭证。
 * 切到库存服务后，入库改为带凭证调库存服务（D §2.4 ①），本服务的登记、质检、处置不变。
 */
@Service
public class IntakeServiceImpl implements IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeServiceImpl.class);
    static final String CABINET = "CABINET";
    static final String POWERBANK = "POWERBANK";
    static final String PENDING = "PENDING";
    static final String PASSED = "PASSED";
    static final String FAILED = "FAILED";
    static final String INBOUND = "INBOUND";
    static final String ARRIVAL = "ARRIVAL";
    static final String OPENING = "OPENING";
    /** 充电宝质检策略（V207）：FULL 逐块质检 / SKIP 免检。 */
    static final String PB_POLICY = "inv.qc.powerbank_policy";
    static final Set<String> DISPOSALS = Set.of("RETURN_SUPPLIER", "SCRAP");
    private static final int MAX_ITEMS = 200;

    private final InvIntakeMapper intakes;
    private final InvIntakeItemMapper items;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final QcService qc;
    private final WarehouseDirectory warehouses;
    private final ai.neargo.sharehub.inv.service.SupplierService suppliers;

    public IntakeServiceImpl(InvIntakeMapper intakes, InvIntakeItemMapper items, CabinetMapper cabinets,
                             PowerbankMapper powerbanks, QcService qc, WarehouseDirectory warehouses,
                             ai.neargo.sharehub.inv.service.SupplierService suppliers) {
        this.suppliers = suppliers;
        this.intakes = intakes;
        this.items = items;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.qc = qc;
        this.warehouses = warehouses;
    }

    /** 不合格处置联动设备终态（报废）。 */
    private ai.neargo.sharehub.dev.service.CabinetLifecycleService lifecycle;
    private MovementRecorder movements;

    @org.springframework.beans.factory.annotation.Autowired
    void setLifecycle(ai.neargo.sharehub.dev.service.CabinetLifecycleService lifecycle, MovementRecorder movements) {
        this.lifecycle = lifecycle;
        this.movements = movements;
    }

    /** 库存账入口（S2.5）：入库经它，切到独立进销存时只换实现。 */
    private ai.neargo.sharehub.inv.port.InventoryPort inventory;

    @org.springframework.beans.factory.annotation.Autowired
    void setInventory(ai.neargo.sharehub.inv.port.InventoryPort inventory) {
        this.inventory = inventory;
    }

    /** 库存参数（V207 质检策略）：setter 注入，构造参数已经很长。 */
    private ai.neargo.sharehub.api.platform.port.SysParamPort params;

    @org.springframework.beans.factory.annotation.Autowired
    void setParams(ai.neargo.sharehub.api.platform.port.SysParamPort params) {
        this.params = params;
    }

    // —— 查询 ——

    @Override
    public PageResult<Intake> page(Integer page, Integer size, String keyword, String status, String warehouseNo) {
        LambdaQueryWrapper<InvIntake> w = new LambdaQueryWrapper<InvIntake>().orderByDesc(InvIntake::getId);
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible != null) {
            if (visible.isEmpty()) return new PageResult<>(List.of(), 0);
            w.in(InvIntake::getWarehouseNo, visible);
        }
        if (notBlank(status)) w.eq(InvIntake::getStatus, status.trim());
        if (notBlank(warehouseNo)) w.eq(InvIntake::getWarehouseNo, warehouseNo.trim());
        if (notBlank(keyword)) {
            String k = keyword.trim();
            w.and(x -> x.like(InvIntake::getIntakeNo, k).or().like(InvIntake::getSourceRef, k));
        }
        Page<InvIntake> p = intakes.selectPage(new Page<>(page == null || page < 1 ? 1 : page,
                size == null || size < 1 ? 20 : Math.min(size, 200)), w);
        Map<String, List<InvIntakeItem>> byNo = p.getRecords().isEmpty() ? Map.of()
                : items.selectList(new LambdaQueryWrapper<InvIntakeItem>().in(InvIntakeItem::getIntakeNo,
                        p.getRecords().stream().map(InvIntake::getIntakeNo).toList()))
                .stream().collect(Collectors.groupingBy(InvIntakeItem::getIntakeNo));
        return new PageResult<>(p.getRecords().stream().map(h -> vo(h, byNo.getOrDefault(h.getIntakeNo(), List.of()))).toList(),
                p.getTotal());
    }

    @Override
    public IntakeDetail get(String intakeNo) {
        InvIntake h = require(intakeNo);
        List<InvIntakeItem> rows = itemsOf(intakeNo);
        Map<String, DevCabinet> cabs = cabinetsOf(rows);
        Map<String, DevPowerbank> pbs = powerbanksOf(rows);
        List<IntakeItem> out = rows.stream().map(r -> {
            String sn = null, model = null;
            if (CABINET.equals(r.getItemType()) && cabs.containsKey(r.getItemNo())) {
                sn = cabs.get(r.getItemNo()).getSn();
                model = cabs.get(r.getItemNo()).getModel();
            } else if (POWERBANK.equals(r.getItemType()) && pbs.containsKey(r.getItemNo())) {
                sn = pbs.get(r.getItemNo()).getSn();
            }
            return new IntakeItem(r.getItemType(), r.getItemNo(), r.getUnitCost(), sn, model, r.getQcNo(), r.getQcResult(),
                    r.getDisposition(), r.getDispositionNote(), r.getHandledBy(), r.getHandledAt());
        }).toList();
        return new IntakeDetail(vo(h, rows), out);
    }

    // —— 登记 ——

    /**
     * 设备须：在库、未绑点位、不在任何商家的出库池、不在别的未处理待检单上。不符的一次列全，整单不建 ——
     * 先拦一台改一台再提交，扫了三十台的人会被同一张单拒三十次。
     */
    @Override
    @Transactional
    public IntakeDetail create(CreateReq r) {
        if (r == null || !notBlank(r.warehouseNo())) throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        warehouses.requireVisible(r.warehouseNo().trim());
        String kind = notBlank(r.kind()) ? r.kind().trim() : ARRIVAL;
        if (!ARRIVAL.equals(kind) && !OPENING.equals(kind)) throw BizException.badRequest("error.common.invalid_value", "kind");
        Map<String, java.math.BigDecimal> costs = r.unitCosts() == null ? Map.of() : r.unitCosts();
        if (costs.values().stream().anyMatch(v -> v != null && v.signum() < 0))
            throw BizException.badRequest("error.common.invalid_value", "unitCost");
        if (costs.values().stream().anyMatch(java.util.Objects::nonNull) && !notBlank(r.currency()))
            throw BizException.badRequest("error.common.missing_parameter", "currency");
        WarehouseItem wh = warehouses.list().stream().filter(x -> x.warehouseNo().equals(r.warehouseNo().trim()) && x.archivedAt() == null)
                .findFirst()
                .orElseThrow(() -> BizException.notFound(r.warehouseNo()));
        String supplierName = notBlank(r.supplierNo()) ? suppliers.activeName(r.supplierNo()) : null;
        if (notBlank(r.supplierNo()) && supplierName == null) throw BizException.notFound(r.supplierNo());
        List<String> cabNos = distinct(r.cabinetNos());
        List<String> pbNos = distinct(r.powerbankNos());
        if (cabNos.isEmpty() && pbNos.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "cabinetNos / powerbankNos");
        if (cabNos.size() + pbNos.size() > MAX_ITEMS) throw BizException.badRequest("error.common.invalid_value", "items > " + MAX_ITEMS);

        List<String> bad = new ArrayList<>();
        Map<String, DevCabinet> cabs = cabNos.isEmpty() ? Map.of() : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                .in(DevCabinet::getCabinetNo, cabNos)).stream().collect(Collectors.toMap(DevCabinet::getCabinetNo, Function.identity(), (a, b) -> a));
        for (String no : cabNos) {
            DevCabinet c = cabs.get(no);
            if (c == null) bad.add(no + "：不存在");
            else if (c.getArchivedAt() != null) bad.add(no + "：已归档");
            else if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) bad.add(no + "：不在库（" + c.getStatus() + "）");
            else if (c.getLocationNo() != null) bad.add(no + "：已绑定点位");
            else if (c.getIssuedAgentNo() != null) bad.add(no + "：已出库给商家");
        }
        Map<String, DevPowerbank> pbs = pbNos.isEmpty() ? Map.of() : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                .in(DevPowerbank::getPowerbankNo, pbNos)).stream().collect(Collectors.toMap(DevPowerbank::getPowerbankNo, Function.identity(), (a, b) -> a));
        for (String no : pbNos) {
            DevPowerbank p = pbs.get(no);
            if (p == null) bad.add(no + "：不存在");
            else if (p.getArchivedAt() != null) bad.add(no + "：已归档");
            else if (!PowerbankStatus.IN_STOCK.name().equals(p.getStatus())) bad.add(no + "：不在库（" + p.getStatus() + "）");
            else if (p.getCabinetNo() != null) bad.add(no + "：在柜内");
        }
        for (InvIntakeItem open : openItems(cabNos, pbNos)) bad.add(open.getItemNo() + "：已在待检单 " + open.getIntakeNo());
        if (!bad.isEmpty()) throw BizException.conflict("error.intake.items_rejected", String.join("；", bad));

        InvIntake h = new InvIntake();
        h.setIntakeNo(IdGenerator.next("IK"));
        h.setTenantId("MAIN");
        h.setKind(kind);
        h.setCurrency(notBlank(r.currency()) ? r.currency().trim().toUpperCase() : null);
        h.setWarehouseNo(wh.warehouseNo());
        h.setWarehouseName(wh.name());
        h.setSupplierNo(supplierName == null ? null : r.supplierNo().trim());
        h.setSupplierName(supplierName);
        h.setSourceRef(blankToNull(r.sourceRef()));
        h.setArrivedAt(notBlank(r.arrivedAt()) ? LocalDateTime.parse(r.arrivedAt().trim().replace(' ', 'T')) : LocalDateTime.now());
        h.setStatus(IntakeStatus.OPEN.name());
        h.setNote(blankToNull(r.note()));
        h.setCreatedBy(operator());
        intakes.insert(h);
        cabNos.forEach(no -> insertItem(h.getIntakeNo(), CABINET, no, costs.get(no)));
        pbNos.forEach(no -> insertItem(h.getIntakeNo(), POWERBANK, no, costs.get(no)));
        // 期初建账：历史在仓、已验收的存量，登记即入库，放行凭证 OPEN-{设备号}（与存量迁移 MIG- 同口径）；
        // 充电宝免检（inv.qc.powerbank_policy = SKIP，V207）：采购到货的宝同样登记即入库，凭证 NOQC-{设备号}，柜照常质检
        boolean opening = OPENING.equals(kind);
        boolean skipPb = !opening && "SKIP".equals(params == null ? null : params.textOf(PB_POLICY, "FULL"));
        if (opening || (skipPb && !pbNos.isEmpty())) {
            String me = operator();
            LocalDateTime now = LocalDateTime.now();
            for (InvIntakeItem i : itemsOf(h.getIntakeNo())) {
                if (!opening && !POWERBANK.equals(i.getItemType())) continue;
                inventory.inbound(i.getItemType(), i.getItemNo(), h.getWarehouseNo(), (opening ? "OPEN-" : "NOQC-") + i.getItemNo(), h.getIntakeNo());
                mark(i, INBOUND, null, me, now);
            }
            closeIfDone(h);
        }
        log.info("入库单登记 kind={} intakeNo={} warehouseNo={} 柜={} 宝={}", kind, h.getIntakeNo(), wh.warehouseNo(), cabNos.size(), pbNos.size());
        return get(h.getIntakeNo());
    }

    /**
     * 撤机回仓（D §2.4 ⑤）：回仓调拨签收时由调拨调用，与签收同一事务。设备已由签收打回待检、离开出库池，
     * 这里不再走登记校验；复检必须新做一条质检记录，旧的放行凭证不再算数。
     */
    @Override
    @Transactional
    public String openReturn(String warehouseNo, String sourceRef, String note, List<String> cabinetNos) {
        List<String> cabNos = distinct(cabinetNos);
        if (cabNos.isEmpty()) return null;
        WarehouseItem wh = warehouses.list().stream().filter(x -> x.warehouseNo().equals(warehouseNo)).findFirst()
                .orElseThrow(() -> BizException.notFound(warehouseNo));
        List<String> open = openItems(cabNos, List.of()).stream().map(InvIntakeItem::getItemNo).toList();
        if (!open.isEmpty()) throw BizException.conflict("error.intake.items_rejected", String.join("；", open));
        InvIntake h = new InvIntake();
        h.setIntakeNo(IdGenerator.next("IK"));
        h.setTenantId("MAIN");
        h.setKind("RETURN");
        h.setWarehouseNo(wh.warehouseNo());
        h.setWarehouseName(wh.name());
        h.setSourceRef(blankToNull(sourceRef));
        h.setArrivedAt(LocalDateTime.now());
        h.setStatus(IntakeStatus.OPEN.name());
        h.setNote(blankToNull(note));
        h.setCreatedBy(operator());
        intakes.insert(h);
        cabNos.forEach(no -> insertItem(h.getIntakeNo(), CABINET, no, null));
        log.info("撤机回仓待检 intakeNo={} warehouseNo={} sourceRef={} 柜={}", h.getIntakeNo(), wh.warehouseNo(), sourceRef, cabNos);
        return h.getIntakeNo();
    }

    // —— 质检 ——

    @Override
    @Transactional
    public IntakeDetail inspect(String intakeNo, String itemType, String itemNo, QcReq req) {
        InvIntake h = requireOpen(intakeNo);
        InvIntakeItem it = requireItem(intakeNo, itemType, itemNo);
        if (!PENDING.equals(it.getDisposition())) throw BizException.conflict("error.intake.item_handled", itemNo);
        QcRecord rec = CABINET.equals(it.getItemType()) ? qc.inspectCabinet(itemNo, req, intakeNo) : qc.inspectPowerbank(itemNo, req, intakeNo);
        items.update(null, new LambdaUpdateWrapper<InvIntakeItem>().eq(InvIntakeItem::getId, it.getId())
                .eq(InvIntakeItem::getDisposition, PENDING)
                .set(InvIntakeItem::getQcNo, rec.qcNo()).set(InvIntakeItem::getQcResult, rec.result()));
        log.info("待检质检 intakeNo={} {}={} result={} qcNo={}", h.getIntakeNo(), itemType, itemNo, rec.result(), rec.qcNo());
        return get(intakeNo);
    }

    // —— 入库 / 处置 ——

    @Override
    @Transactional
    public IntakeDetail inbound(String intakeNo, ItemsReq req) {
        InvIntake h = requireOpen(intakeNo);
        List<InvIntakeItem> picked = pick(intakeNo, req == null ? null : req.items());
        List<String> bad = picked.stream().filter(i -> !PENDING.equals(i.getDisposition()) || !PASSED.equals(i.getQcResult()))
                .map(i -> i.getItemNo() + "：" + (PENDING.equals(i.getDisposition()) ? "质检未通过或未质检" : "已处理")).toList();
        if (!bad.isEmpty()) throw BizException.conflict("error.intake.items_rejected", String.join("；", bad));
        String me = operator();
        LocalDateTime now = LocalDateTime.now();
        for (InvIntakeItem i : picked) {
            // 放行凭证一次性，且须是本单上做的质检（回仓设备旧的通过不能复用）
            if (!qc.consume(i.getQcNo(), i.getItemNo(), intakeNo)) throw BizException.conflict("error.intake.voucher_invalid", i.getItemNo());
            inventory.inbound(i.getItemType(), i.getItemNo(), h.getWarehouseNo(), i.getQcNo(), intakeNo);
            mark(i, INBOUND, null, me, now);
        }
        closeIfDone(h);
        log.info("待检入库 intakeNo={} warehouseNo={} 台数={}", intakeNo, h.getWarehouseNo(), picked.size());
        return get(intakeNo);
    }

    @Override
    @Transactional
    public IntakeDetail dispose(String intakeNo, DisposeReq req) {
        InvIntake h = requireOpen(intakeNo);
        if (req == null || !DISPOSALS.contains(req.disposition())) throw BizException.badRequest("error.common.invalid_value", "disposition");
        if (!notBlank(req.note())) throw BizException.badRequest("error.common.note_required");
        List<InvIntakeItem> picked = pick(intakeNo, req.items());
        List<String> bad = picked.stream().filter(i -> !PENDING.equals(i.getDisposition()) || !FAILED.equals(i.getQcResult()))
                .map(i -> i.getItemNo() + "：" + (PENDING.equals(i.getDisposition()) ? "质检未判不合格" : "已处理")).toList();
        if (!bad.isEmpty()) throw BizException.conflict("error.intake.items_rejected", String.join("；", bad));
        String me = operator();
        LocalDateTime now = LocalDateTime.now();
        String why = ("SCRAP".equals(req.disposition()) ? "报废" : "退供应商") + "（待检单 " + intakeNo + "）：" + req.note().trim();
        for (InvIntakeItem i : picked) {
            // 退供应商与报废都是离开库存、不再回来：设备本身也要落到终态，否则它在台账上是「在库、不在任何仓」的幽灵
            if (CABINET.equals(i.getItemType())) {
                lifecycle.retire(i.getItemNo(), why);
            } else {
                int n = powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, i.getItemNo())
                        .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).set(DevPowerbank::getStatus, PowerbankStatus.SCRAP.name()));
                if (n == 0) throw BizException.conflict("error.common.state_changed");
                movements.record(new MovementRecorder.Move(POWERBANK, i.getItemNo(), MovementEvent.RETIRE, null, MovementRecorder.OUT,
                        null, null, why, intakeNo));
            }
            mark(i, req.disposition(), req.note().trim(), me, now);
        }
        closeIfDone(h);
        log.info("待检处置 intakeNo={} disposition={} 台数={}", intakeNo, req.disposition(), picked.size());
        return get(intakeNo);
    }

    // —— 内部 ——

    private void insertItem(String intakeNo, String type, String no, java.math.BigDecimal unitCost) {
        InvIntakeItem i = new InvIntakeItem();
        i.setIntakeNo(intakeNo);
        i.setItemType(type);
        i.setItemNo(no);
        i.setUnitCost(unitCost);
        i.setQcResult(PENDING);
        i.setDisposition(PENDING);
        items.insert(i);
    }

    private void mark(InvIntakeItem i, String disposition, String note, String me, LocalDateTime now) {
        int n = items.update(null, new LambdaUpdateWrapper<InvIntakeItem>().eq(InvIntakeItem::getId, i.getId())
                .eq(InvIntakeItem::getDisposition, PENDING)
                .set(InvIntakeItem::getDisposition, disposition).set(InvIntakeItem::getDispositionNote, note)
                .set(InvIntakeItem::getHandledBy, me).set(InvIntakeItem::getHandledAt, now));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
    }

    private void closeIfDone(InvIntake h) {
        Long left = items.selectCount(new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getIntakeNo, h.getIntakeNo())
                .eq(InvIntakeItem::getDisposition, PENDING));
        if (left == null || left == 0) {
            intakes.update(null, new LambdaUpdateWrapper<InvIntake>().eq(InvIntake::getIntakeNo, h.getIntakeNo())
                    .eq(InvIntake::getStatus, IntakeStatus.OPEN.name()).set(InvIntake::getStatus, IntakeStatus.DONE.name()));
        }
    }

    private List<InvIntakeItem> pick(String intakeNo, List<ItemRef> refs) {
        if (refs == null || refs.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "items");
        List<InvIntakeItem> out = new ArrayList<>();
        for (ItemRef ref : new LinkedHashSet<>(refs)) out.add(requireItem(intakeNo, ref.itemType(), ref.itemNo()));
        return out;
    }

    private List<InvIntakeItem> openItems(List<String> cabNos, List<String> pbNos) {
        List<InvIntakeItem> out = new ArrayList<>();
        if (!cabNos.isEmpty()) out.addAll(items.selectList(new LambdaQueryWrapper<InvIntakeItem>()
                .eq(InvIntakeItem::getItemType, CABINET).in(InvIntakeItem::getItemNo, cabNos).eq(InvIntakeItem::getDisposition, PENDING)));
        if (!pbNos.isEmpty()) out.addAll(items.selectList(new LambdaQueryWrapper<InvIntakeItem>()
                .eq(InvIntakeItem::getItemType, POWERBANK).in(InvIntakeItem::getItemNo, pbNos).eq(InvIntakeItem::getDisposition, PENDING)));
        return out;
    }

    private List<InvIntakeItem> itemsOf(String intakeNo) {
        return items.selectList(new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getIntakeNo, intakeNo).orderByAsc(InvIntakeItem::getId));
    }

    private Map<String, DevCabinet> cabinetsOf(List<InvIntakeItem> rows) {
        List<String> nos = rows.stream().filter(r -> CABINET.equals(r.getItemType())).map(InvIntakeItem::getItemNo).toList();
        return nos.isEmpty() ? Map.of() : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().in(DevCabinet::getCabinetNo, nos))
                .stream().collect(Collectors.toMap(DevCabinet::getCabinetNo, Function.identity(), (a, b) -> a));
    }

    private Map<String, DevPowerbank> powerbanksOf(List<InvIntakeItem> rows) {
        List<String> nos = rows.stream().filter(r -> POWERBANK.equals(r.getItemType())).map(InvIntakeItem::getItemNo).toList();
        return nos.isEmpty() ? Map.of() : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().in(DevPowerbank::getPowerbankNo, nos))
                .stream().collect(Collectors.toMap(DevPowerbank::getPowerbankNo, Function.identity(), (a, b) -> a));
    }

    private InvIntake require(String intakeNo) {
        InvIntake h = intakes.selectOne(new LambdaQueryWrapper<InvIntake>().eq(InvIntake::getIntakeNo, intakeNo).last("limit 1"));
        if (h == null) throw BizException.notFound(intakeNo);
        warehouses.requireVisible(h.getWarehouseNo());
        return h;
    }

    private InvIntake requireOpen(String intakeNo) {
        InvIntake h = require(intakeNo);
        if (!IntakeStatus.OPEN.name().equals(h.getStatus())) throw BizException.conflict("error.intake.closed", intakeNo);
        return h;
    }

    private InvIntakeItem requireItem(String intakeNo, String itemType, String itemNo) {
        InvIntakeItem i = items.selectOne(new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getIntakeNo, intakeNo)
                .eq(InvIntakeItem::getItemType, itemType).eq(InvIntakeItem::getItemNo, itemNo).last("limit 1"));
        if (i == null) throw BizException.notFound(itemNo);
        return i;
    }

    private static Intake vo(InvIntake h, List<InvIntakeItem> rows) {
        int pending = 0, passed = 0, failed = 0, inbound = 0, disposed = 0;
        for (InvIntakeItem r : rows) {
            if (INBOUND.equals(r.getDisposition())) inbound++;
            else if (DISPOSALS.contains(r.getDisposition())) disposed++;
            else if (PASSED.equals(r.getQcResult())) passed++;
            else if (FAILED.equals(r.getQcResult())) failed++;
            else pending++;
        }
        return new Intake(h.getIntakeNo(), h.getKind(), h.getWarehouseNo(), h.getWarehouseName(), h.getSourceRef(),
                h.getSupplierNo(), h.getSupplierName(), h.getCurrency(),
                rows.stream().map(InvIntakeItem::getUnitCost).filter(java.util.Objects::nonNull)
                        .reduce(java.math.BigDecimal::add).orElse(null),
                h.getArrivedAt(), h.getStatus(), h.getNote(), rows.size(), pending, passed, failed, inbound, disposed,
                h.getCreatedBy(), h.getCreatedAt());
    }

    private static List<String> distinct(List<String> xs) {
        return xs == null ? List.of() : xs.stream().filter(IntakeServiceImpl::notBlank).map(String::trim).distinct().toList();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }
}
