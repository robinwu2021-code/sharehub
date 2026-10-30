package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import ai.neargo.sharehub.inv.DisposalKind;
import ai.neargo.sharehub.inv.DisposalReason;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.DisposalDtos.Disposal;
import ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalDetail;
import ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalItem;
import ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalReq;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.entity.InvDisposal;
import ai.neargo.sharehub.inv.entity.InvDisposalItem;
import ai.neargo.sharehub.inv.entity.InvIntakeItem;
import ai.neargo.sharehub.inv.mapper.InvDisposalItemMapper;
import ai.neargo.sharehub.inv.mapper.InvDisposalMapper;
import ai.neargo.sharehub.inv.mapper.InvIntakeItemMapper;
import ai.neargo.sharehub.inv.port.InventoryPort;
import ai.neargo.sharehub.inv.service.DisposalService;
import ai.neargo.sharehub.inv.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 处置出库（V203），规则见 {@link DisposalService}。 */
@Service
public class DisposalServiceImpl implements DisposalService {

    private static final Logger log = LoggerFactory.getLogger(DisposalServiceImpl.class);
    private static final int MAX_ITEMS = 200;

    private final InvDisposalMapper disposals;
    private final InvDisposalItemMapper items;
    private final InvIntakeItemMapper intakeItems;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final CabinetLifecycleService lifecycle;
    private final MovementRecorder movements;
    private final WarehouseDirectory warehouses;
    private final SupplierService suppliers;
    private final InventoryPort inventory;
    private ai.neargo.sharehub.inv.service.ShipmentService shipments;

    /** 物流（V205）：setter 注入，构造参数已经很长。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setShipments(ai.neargo.sharehub.inv.service.ShipmentService shipments) {
        this.shipments = shipments;
    }

    public DisposalServiceImpl(InvDisposalMapper disposals, InvDisposalItemMapper items, InvIntakeItemMapper intakeItems,
                               CabinetMapper cabinets, PowerbankMapper powerbanks, CabinetLifecycleService lifecycle,
                               MovementRecorder movements, WarehouseDirectory warehouses, SupplierService suppliers,
                               InventoryPort inventory) {
        this.disposals = disposals;
        this.items = items;
        this.intakeItems = intakeItems;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.lifecycle = lifecycle;
        this.movements = movements;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.inventory = inventory;
    }

    @Override
    public PageResult<Disposal> page(Integer page, Integer size, String keyword, String kind, String warehouseNo, String supplierNo) {
        LambdaQueryWrapper<InvDisposal> w = new LambdaQueryWrapper<InvDisposal>().orderByDesc(InvDisposal::getId);
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible != null) {
            if (visible.isEmpty()) return new PageResult<>(List.of(), 0);
            w.in(InvDisposal::getWarehouseNo, visible);
        }
        if (notBlank(kind)) w.eq(InvDisposal::getKind, kind.trim());
        if (notBlank(warehouseNo)) w.eq(InvDisposal::getWarehouseNo, warehouseNo.trim());
        if (notBlank(supplierNo)) w.eq(InvDisposal::getSupplierNo, supplierNo.trim());
        if (notBlank(keyword)) {
            String k = keyword.trim();
            List<String> byItem = items.selectList(new LambdaQueryWrapper<InvDisposalItem>().eq(InvDisposalItem::getItemNo, k))
                    .stream().map(InvDisposalItem::getDisposalNo).toList();
            w.and(x -> {
                x.like(InvDisposal::getDisposalNo, k).or().like(InvDisposal::getNote, k);
                if (!byItem.isEmpty()) x.or().in(InvDisposal::getDisposalNo, byItem);
            });
        }
        Page<InvDisposal> p = disposals.selectPage(new Page<>(page == null || page < 1 ? 1 : page,
                size == null || size < 1 ? 20 : Math.min(size, 200)), w);
        return new PageResult<>(p.getRecords().stream().map(DisposalServiceImpl::vo).toList(), p.getTotal());
    }

    @Override
    public DisposalDetail get(String disposalNo) {
        InvDisposal d = disposalNo == null ? null
                : disposals.selectOne(new LambdaQueryWrapper<InvDisposal>().eq(InvDisposal::getDisposalNo, disposalNo).last("limit 1"));
        if (d == null) throw BizException.notFound(disposalNo);
        warehouses.requireVisible(d.getWarehouseNo());
        List<DisposalItem> rows = items.selectList(new LambdaQueryWrapper<InvDisposalItem>().eq(InvDisposalItem::getDisposalNo, disposalNo)
                .orderByAsc(InvDisposalItem::getId)).stream().map(i -> new DisposalItem(i.getItemType(), i.getItemNo())).toList();
        return new DisposalDetail(vo(d), rows);
    }

    @Override
    @Transactional
    public DisposalDetail create(DisposalReq r) {
        if (r == null || !notBlank(r.kind())) throw BizException.badRequest("error.common.missing_parameter", "kind");
        DisposalKind kind = Arrays.stream(DisposalKind.values()).filter(k -> k.name().equals(r.kind().trim())).findFirst()
                .orElseThrow(() -> BizException.badRequest("error.common.invalid_value", "kind"));
        if (!notBlank(r.note())) throw BizException.badRequest("error.common.note_required");
        if (!notBlank(r.warehouseNo())) throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        String reason = null;
        String supplierNo = null;
        String supplierName = null;
        if (kind == DisposalKind.SCRAP) {
            if (notBlank(r.supplierNo())) throw BizException.badRequest("error.common.invalid_value", "supplierNo");
            reason = Arrays.stream(DisposalReason.values()).map(Enum::name).filter(x -> x.equals(r.reasonCode() == null ? null : r.reasonCode().trim()))
                    .findFirst().orElseThrow(() -> BizException.badRequest("error.common.invalid_value", "reasonCode"));
        } else {
            if (!notBlank(r.supplierNo())) throw BizException.badRequest("error.common.missing_parameter", "supplierNo");
            supplierNo = r.supplierNo().trim();
            supplierName = suppliers.activeName(supplierNo);
            if (supplierName == null) throw BizException.notFound(supplierNo);
        }
        WarehouseItem wh = warehouses.list().stream().filter(x -> x.warehouseNo().equals(r.warehouseNo().trim()) && x.archivedAt() == null)
                .findFirst().orElseThrow(() -> BizException.notFound(r.warehouseNo()));

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
            else if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) bad.add(no + "：不在库（" + c.getStatus() + "）");
            else if (c.getLocationNo() != null) bad.add(no + "：已绑定点位");
            else if (c.getIssuedAgentNo() != null) bad.add(no + "：已出库给商家");
            else if (!wh.warehouseNo().equals(c.getWarehouseNo())) bad.add(no + "：不在 " + wh.name());
        }
        Map<String, DevPowerbank> pbs = pbNos.isEmpty() ? Map.of() : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                .in(DevPowerbank::getPowerbankNo, pbNos)).stream().collect(Collectors.toMap(DevPowerbank::getPowerbankNo, Function.identity(), (a, b) -> a));
        for (String no : pbNos) {
            DevPowerbank p = pbs.get(no);
            if (p == null) bad.add(no + "：不存在");
            else if (!PowerbankStatus.IN_STOCK.name().equals(p.getStatus())) bad.add(no + "：不在库（" + p.getStatus() + "）");
            else if (p.getCabinetNo() != null) bad.add(no + "：在柜内");
            else if (!wh.warehouseNo().equals(p.getWarehouseNo())) bad.add(no + "：不在 " + wh.name());
        }
        List<String> all = new ArrayList<>(cabNos);
        all.addAll(pbNos);
        intakeItems.selectList(new LambdaQueryWrapper<InvIntakeItem>().in(InvIntakeItem::getItemNo, all)
                        .eq(InvIntakeItem::getDisposition, IntakeServiceImpl.PENDING))
                .forEach(i -> bad.add(i.getItemNo() + "：在未结待检单 " + i.getIntakeNo() + " 上，请在待检单里处置"));
        if (!bad.isEmpty()) throw BizException.conflict("error.intake.items_rejected", String.join("；", bad));
        ai.neargo.sharehub.inv.dto.ShipmentDtos.ShipInfo ship = kind == DisposalKind.RETURN_SUPPLIER ? new ai.neargo.sharehub.inv.dto.ShipmentDtos.ShipInfo(r.carrier(), r.trackingNo(), null, null) : null;
        shipments.validate(ship);

        InvDisposal d = new InvDisposal();
        d.setDisposalNo(IdGenerator.next("DP"));
        d.setTenantId("MAIN");
        d.setKind(kind.name());
        d.setWarehouseNo(wh.warehouseNo());
        d.setWarehouseName(wh.name());
        d.setSupplierNo(supplierNo);
        d.setSupplierName(supplierName);
        d.setReasonCode(reason);
        d.setNote(r.note().trim());
        d.setItemCount(all.size());
        d.setCreatedBy(operator());
        disposals.insert(d);

        String why = (kind == DisposalKind.SCRAP ? "报废" : "退供应商 " + supplierName) + "（处置出库 " + d.getDisposalNo() + "）：" + d.getNote();
        for (String no : cabNos) {
            insertItem(d.getDisposalNo(), MovementRecorder.CABINET, no);
            lifecycle.retire(no, why);
        }
        for (String no : pbNos) {
            insertItem(d.getDisposalNo(), MovementRecorder.POWERBANK, no);
            int n = powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, no)
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).set(DevPowerbank::getStatus, PowerbankStatus.SCRAP.name()));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            movements.record(new MovementRecorder.Move(MovementRecorder.POWERBANK, no, MovementEvent.RETIRE, MovementRecorder.wh(wh.warehouseNo()),
                    MovementRecorder.OUT, null, null, why, d.getDisposalNo()));
        }
        if (!"LOCAL".equals(inventory.engine())) remoteOutbound(kind, reason, supplierNo, supplierName, wh.warehouseNo(), cabNos, pbNos, d);
        if (ship != null) shipments.record(ai.neargo.sharehub.inv.ShipmentDocType.DISPOSAL, d.getDisposalNo(),
                Map.of(MovementRecorder.CABINET, cabNos, MovementRecorder.POWERBANK, pbNos), ship);
        log.info("处置出库 disposalNo={} kind={} warehouseNo={} supplierNo={} 柜={} 宝={}", d.getDisposalNo(), kind,
                wh.warehouseNo(), supplierNo, cabNos.size(), pbNos.size());
        return get(d.getDisposalNo());
    }

    /**
     * 独立进销存引擎（V210 起支持）：同一事务里在库存服务做一张出库 —— 报废 SCRAP 带原因码，退供应商 RETURN_SUPPLIER 带
     * SUPPLIER 去向（先同步供应商为外部目标）。库存服务拒绝即整单回滚，两本账不会一边有一边没有。
     */
    private void remoteOutbound(DisposalKind kind, String reason, String supplierNo, String supplierName, String warehouseNo,
                                List<String> cabNos, List<String> pbNos, InvDisposal d) {
        ai.neargo.sharehub.inv.remote.InventoryApi api = remote.api();
        ai.neargo.sharehub.inv.remote.InventoryCalls.requireConfigured(api);
        ai.neargo.sharehub.inv.remote.InventoryCalls.run(() -> {
            String loc = links.locationId(api, warehouseNo);
            // 确保两种设备的物料已在库存服务登记（出库按序列号，不需要 itemId，但首次接入时要有物料）
            if (!cabNos.isEmpty()) links.itemId(api, MovementRecorder.CABINET);
            if (!pbNos.isEmpty()) links.itemId(api, MovementRecorder.POWERBANK);
            java.util.Map<String, String> targets = java.util.Map.of();
            if (kind == DisposalKind.RETURN_SUPPLIER) {
                api.upsertTarget("SUPPLIER", supplierNo, supplierName, null);
                targets = java.util.Map.of("SUPPLIER", supplierNo);
            }
            List<String> sns = new ArrayList<>(cabNos);
            sns.addAll(pbNos);
            String out = api.createOutbound(loc, kind.name(), reason, targets, "处置出库 " + d.getDisposalNo() + "：" + d.getNote(),
                    List.of(), sns, "dp-out-" + d.getDisposalNo());
            api.postOutbound(out, "dp-out-post-" + d.getDisposalNo());
            log.info("处置出库同步库存服务 disposalNo={} outNo={}", d.getDisposalNo(), out);
        });
    }

    private ai.neargo.sharehub.inv.remote.RemoteInventoryPort remote;
    private ai.neargo.sharehub.inv.remote.InventoryLinks links;

    /** 独立进销存（V210）：setter 注入，构造参数已经很长。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setRemote(ai.neargo.sharehub.inv.remote.RemoteInventoryPort remote, ai.neargo.sharehub.inv.remote.InventoryLinks links) {
        this.remote = remote;
        this.links = links;
    }

    private void insertItem(String disposalNo, String type, String no) {
        InvDisposalItem i = new InvDisposalItem();
        i.setDisposalNo(disposalNo);
        i.setItemType(type);
        i.setItemNo(no);
        items.insert(i);
    }

    private static Disposal vo(InvDisposal d) {
        return new Disposal(d.getDisposalNo(), d.getKind(), d.getWarehouseNo(), d.getWarehouseName(), d.getSupplierNo(),
                d.getSupplierName(), d.getReasonCode(), d.getNote(), d.getItemCount() == null ? 0 : d.getItemCount(),
                d.getCreatedBy(), d.getCreatedAt());
    }

    private static List<String> distinct(List<String> xs) {
        return xs == null ? List.of() : xs.stream().filter(DisposalServiceImpl::notBlank).map(String::trim).distinct().toList();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }
}
