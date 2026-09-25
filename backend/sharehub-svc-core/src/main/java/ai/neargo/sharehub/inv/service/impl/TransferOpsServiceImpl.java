package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.QcStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import ai.neargo.sharehub.inv.AssetDiffKind;
import ai.neargo.sharehub.inv.AssetDiffStatus;
import ai.neargo.sharehub.inv.InvTransferStateMachine;
import ai.neargo.sharehub.inv.InvTransferStatus;
import ai.neargo.sharehub.inv.TransferEndpointType;
import ai.neargo.sharehub.inv.TransferSource;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransfer;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.dto.InvDtos.TransferItem;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.AssetDiff;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ReceiveResult;
import ai.neargo.sharehub.inv.entity.InvAssetDiff;
import ai.neargo.sharehub.inv.entity.InvTransfer;
import ai.neargo.sharehub.inv.entity.InvTransferItem;
import ai.neargo.sharehub.inv.entity.InvWarehouse;
import ai.neargo.sharehub.inv.mapper.InvAssetDiffMapper;
import ai.neargo.sharehub.inv.mapper.InvTransferItemMapper;
import ai.neargo.sharehub.inv.mapper.InvTransferMapper;
import ai.neargo.sharehub.inv.mapper.InvWarehouseMapper;
import ai.neargo.sharehub.inv.service.TransferOpsService;
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
import java.util.Set;

/**
 * 调拨作业实现（C4 / C8）。
 *
 * <p><b>签收不因差异而卡住</b>：少了一台时整单拒收只会让另外九台也回不了库。签收照常完成，
 * 差异逐件落 {@code inv_asset_diff} 等人查清去向 —— 「少了一台」当场被记下，而不是月底盘点才发现。
 */
@Service
public class TransferOpsServiceImpl implements TransferOpsService {

    private static final Logger log = LoggerFactory.getLogger(TransferOpsServiceImpl.class);
    private static final String CABINET = "CABINET";
    private static final String POWERBANK = "POWERBANK";

    private final InvTransferMapper transfers;
    private final InvTransferItemMapper items;
    private final InvAssetDiffMapper diffs;
    private final InvWarehouseMapper warehouses;
    private final InvTransferStateMachine sm;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final CabinetLifecycleService lifecycle;
    private final SysParamPort params;

    public TransferOpsServiceImpl(InvTransferMapper transfers, InvTransferItemMapper items, InvAssetDiffMapper diffs,
                                  InvWarehouseMapper warehouses, InvTransferStateMachine sm, CabinetMapper cabinets,
                                  PowerbankMapper powerbanks, CabinetLifecycleService lifecycle, SysParamPort params) {
        this.transfers = transfers;
        this.items = items;
        this.diffs = diffs;
        this.warehouses = warehouses;
        this.sm = sm;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.lifecycle = lifecycle;
        this.params = params;
    }

    // —— 明细 ——

    @Override
    @Transactional
    public InventoryTransferDetail setItems(String transferNo, List<String> itemNos) {
        InvTransfer t = require(transferNo);
        if (!InvTransferStatus.DRAFT.name().equals(t.getStatus())) throw BizException.conflict("error.transfer.draft_only", transferNo);
        List<String> nos = itemNos == null ? List.of() : itemNos.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (nos.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "itemNos");
        for (String no : nos) checkShippable(t.getItemType(), no);
        items.delete(new LambdaQueryWrapper<InvTransferItem>().eq(InvTransferItem::getTransferNo, transferNo));
        for (String no : nos) {
            InvTransferItem i = new InvTransferItem();
            i.setTenantId("MAIN");
            i.setTransferNo(transferNo);
            if (CABINET.equals(t.getItemType())) i.setCabinetNo(no); else i.setPowerbankNo(no);
            i.setChecked(0);
            items.insert(i);
        }
        transfers.update(null, new LambdaUpdateWrapper<InvTransfer>().eq(InvTransfer::getTransferNo, transferNo)
                .set(InvTransfer::getPowerbankCount, nos.size()));
        return detail(transferNo);
    }

    /** 能不能装车：存在、在库、质检已过。 */
    private void checkShippable(String itemType, String no) {
        if (CABINET.equals(itemType)) {
            DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, no).last("limit 1"));
            if (c == null) throw BizException.notFound(no);
            if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) throw BizException.conflict("error.transfer.item_not_in_stock", no);
            if (!QcStatus.cleared(c.getQcStatus())) throw BizException.conflict("error.device.qc_not_passed", no);
        } else {
            DevPowerbank p = powerbanks.selectOne(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, no).last("limit 1"));
            if (p == null) throw BizException.notFound(no);
            if (!PowerbankStatus.IN_STOCK.name().equals(p.getStatus())) throw BizException.conflict("error.transfer.item_not_in_stock", no);
            if (!QcStatus.cleared(p.getQcStatus())) throw BizException.conflict("error.device.qc_not_passed", no);
        }
    }

    // —— 发货 ——

    @Override
    @Transactional
    public InventoryTransferDetail ship(String transferNo) {
        InvTransfer t = require(transferNo);
        String to = sm.next(t.getStatus(), "SHIP");
        List<InvTransferItem> list = itemsOf(transferNo);
        // 发货这一刻再核一遍：建明细之后到装车之间，柜子可能被别的单调走或质检被判不过
        for (InvTransferItem i : list) checkShippable(t.getItemType(), itemNo(i));
        int n = transfers.update(null, new LambdaUpdateWrapper<InvTransfer>().eq(InvTransfer::getTransferNo, transferNo)
                .eq(InvTransfer::getStatus, t.getStatus()).set(InvTransfer::getStatus, to).set(InvTransfer::getShippedAt, LocalDateTime.now()));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        if (CABINET.equals(t.getItemType())) {
            for (InvTransferItem i : list) lifecycle.ship(i.getCabinetNo(), transferNo);
        }
        log.info("调拨发货 transferNo={} items={}", transferNo, list.size());
        return detail(transferNo);
    }

    // —— 签收 ——

    @Override
    @Transactional
    public ReceiveResult receive(String transferNo, List<String> receivedNos, String note) {
        InvTransfer t = require(transferNo);
        String to = sm.next(t.getStatus(), "RECEIVE");
        List<InvTransferItem> list = itemsOf(transferNo);
        Set<String> expected = new LinkedHashSet<>();
        list.forEach(i -> expected.add(itemNo(i)));
        Set<String> got = new LinkedHashSet<>();
        if (receivedNos != null) receivedNos.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(got::add);
        if (!expected.isEmpty() && got.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "receivedNos");

        String me = operator();
        LocalDateTime now = LocalDateTime.now();
        int n = transfers.update(null, new LambdaUpdateWrapper<InvTransfer>().eq(InvTransfer::getTransferNo, transferNo)
                .eq(InvTransfer::getStatus, t.getStatus()).set(InvTransfer::getStatus, to).set(InvTransfer::getReceivedAt, now)
                .set(InvTransfer::getReceivedBy, me).set(InvTransfer::getReceiveNote, note == null || note.isBlank() ? null : note.trim()));
        if (n == 0) throw BizException.conflict("error.common.state_changed");

        String warehouseNo = TransferEndpointType.WAREHOUSE.name().equals(t.getToType()) ? t.getToRef() : null;
        List<String> missing = new ArrayList<>();
        List<AssetDiff> out = new ArrayList<>();
        int received = 0;
        for (InvTransferItem i : list) {
            String no = itemNo(i);
            if (!got.contains(no)) {
                missing.add(no);
                out.add(vo(diff("TRANSFER", transferNo, AssetDiffKind.MISSING, t.getItemType(), no, null, 1, 0)));
                continue;
            }
            received++;
            items.update(null, new LambdaUpdateWrapper<InvTransferItem>().eq(InvTransferItem::getId, i.getId()).set(InvTransferItem::getChecked, 1));
            if (CABINET.equals(t.getItemType())) {
                lifecycle.receive(no, warehouseNo);
            } else if (warehouseNo != null) {
                powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, no)
                        .set(DevPowerbank::getWarehouseNo, warehouseNo));
            }
        }
        List<String> extra = got.stream().filter(no -> !expected.contains(no)).toList();
        for (String no : extra) out.add(vo(diff("TRANSFER", transferNo, AssetDiffKind.EXTRA, t.getItemType(), no, null, 0, 1)));
        if (!out.isEmpty()) {
            // 需要人去查的是这些件的去向：谁该做什么 = 仓管按差异单逐件追查
            log.warn("调拨签收有差异 transferNo={} missing={} extra={}，已落资产差异，请仓管逐件追查", transferNo, missing, extra);
        }
        return new ReceiveResult(transferNo, to, received, missing, extra, out);
    }

    // —— 差异 ——

    @Override
    public PageResult<AssetDiff> diffs(Integer page, Integer size, String status, String sourceType, String sourceRef) {
        LambdaQueryWrapper<InvAssetDiff> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(InvAssetDiff::getStatus, AssetDiffStatus.valueOf(status.trim().toUpperCase()).name());
        if (sourceType != null && !sourceType.isBlank()) w.eq(InvAssetDiff::getSourceType, sourceType.trim().toUpperCase());
        if (sourceRef != null && !sourceRef.isBlank()) w.eq(InvAssetDiff::getSourceRef, sourceRef.trim());
        w.orderByDesc(InvAssetDiff::getId);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        Page<InvAssetDiff> r = diffs.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(TransferOpsServiceImpl::vo).toList(), r.getTotal());
    }

    @Override
    @Transactional
    public AssetDiff resolveDiff(String diffNo, String note) {
        if (note == null || note.isBlank()) throw BizException.badRequest("error.common.note_required");
        int n = diffs.update(null, new LambdaUpdateWrapper<InvAssetDiff>().eq(InvAssetDiff::getDiffNo, diffNo)
                .eq(InvAssetDiff::getStatus, AssetDiffStatus.OPEN.name()).set(InvAssetDiff::getStatus, AssetDiffStatus.RESOLVED.name())
                .set(InvAssetDiff::getResolveNote, note.trim()).set(InvAssetDiff::getResolvedBy, operator())
                .set(InvAssetDiff::getResolvedAt, LocalDateTime.now()));
        InvAssetDiff d = diffs.selectOne(new LambdaQueryWrapper<InvAssetDiff>().eq(InvAssetDiff::getDiffNo, diffNo).last("limit 1"));
        if (d == null) throw BizException.notFound(diffNo);
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        return vo(d);
    }

    // —— 撤机（C8）——

    @Override
    @Transactional
    public String openReturn(String siteNo, String siteName, String cabinetNo, String woNo) {
        InvTransfer existing = transfers.selectOne(new LambdaQueryWrapper<InvTransfer>()
                .eq(InvTransfer::getSourceType, TransferSource.REMOVAL.name()).eq(InvTransfer::getSourceRef, woNo).last("limit 1"));
        if (existing != null) return existing.getTransferNo();
        InvWarehouse wh = returnWarehouse();
        if (wh == null) {
            log.warn("撤机回仓单未生成 woNo={} cabinetNo={}：没有可用仓库，请先在库存管理里建仓并配置 inv.return_warehouse", woNo, cabinetNo);
            return null;
        }
        InvTransfer t = new InvTransfer();
        t.setTransferNo(IdGenerator.next(BizKey.TRANSFER));
        t.setTenantId("MAIN");
        t.setFromType(TransferEndpointType.SITE.name());
        t.setFromRef(siteNo);
        t.setFromName(siteName == null ? siteNo : siteName);
        t.setToType(TransferEndpointType.WAREHOUSE.name());
        t.setToRef(wh.getWarehouseNo());
        t.setToName(wh.getName());
        t.setItemType(CABINET);
        t.setPowerbankCount(1);
        t.setStatus(InvTransferStatus.DRAFT.name());
        t.setOperatorNo(operator());
        t.setSourceType(TransferSource.REMOVAL.name());
        t.setSourceRef(woNo);
        transfers.insert(t);
        InvTransferItem i = new InvTransferItem();
        i.setTenantId("MAIN");
        i.setTransferNo(t.getTransferNo());
        i.setCabinetNo(cabinetNo);
        i.setChecked(0);
        items.insert(i);
        log.info("撤机生成回仓调拨 transferNo={} cabinetNo={} woNo={} → {}", t.getTransferNo(), cabinetNo, woNo, wh.getWarehouseNo());
        return t.getTransferNo();
    }

    @Override
    @Transactional
    public AssetDiff recordCountMismatch(String sourceType, String woNo, String siteNo, String cabinetNo, int expected, int actual) {
        return vo(diff(sourceType, woNo, AssetDiffKind.COUNT_MISMATCH, POWERBANK, null, cabinetNo, expected, actual, siteNo));
    }

    private InvWarehouse returnWarehouse() {
        // 配了且存在就用配的仓；否则取第一个仓 —— 单仓运营时不必额外配置
        String configured = params.textOf("inv.return_warehouse", null);
        if (configured != null) {
            InvWarehouse w = warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo, configured).last("limit 1"));
            if (w != null) return w;
        }
        return warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().orderByAsc(InvWarehouse::getId).last("limit 1"));
    }

    // —— 工具 ——

    private InvAssetDiff diff(String sourceType, String sourceRef, AssetDiffKind kind, String itemType, String itemNo,
                              String cabinetNo, Integer expected, Integer actual) {
        return diff(sourceType, sourceRef, kind, itemType, itemNo, cabinetNo, expected, actual, null);
    }

    private InvAssetDiff diff(String sourceType, String sourceRef, AssetDiffKind kind, String itemType, String itemNo,
                              String cabinetNo, Integer expected, Integer actual, String siteNo) {
        InvAssetDiff d = new InvAssetDiff();
        d.setDiffNo(IdGenerator.next("AD"));
        d.setTenantId("MAIN");
        d.setSourceType(sourceType);
        d.setSourceRef(sourceRef);
        d.setKind(kind.name());
        d.setItemType(itemType);
        d.setItemNo(itemNo);
        d.setSiteNo(siteNo);
        d.setCabinetNo(cabinetNo);
        d.setExpectedQty(expected);
        d.setActualQty(actual);
        d.setStatus(AssetDiffStatus.OPEN.name());
        diffs.insert(d);
        return d;
    }

    private InvTransfer require(String transferNo) {
        InvTransfer t = transfers.selectOne(new LambdaQueryWrapper<InvTransfer>().eq(InvTransfer::getTransferNo, transferNo).last("limit 1"));
        if (t == null) throw BizException.notFound(transferNo);
        return t;
    }

    private List<InvTransferItem> itemsOf(String transferNo) {
        return items.selectList(new LambdaQueryWrapper<InvTransferItem>().eq(InvTransferItem::getTransferNo, transferNo)
                .orderByAsc(InvTransferItem::getId));
    }

    private InventoryTransferDetail detail(String transferNo) {
        InvTransfer e = require(transferNo);
        List<TransferItem> rows = itemsOf(transferNo).stream()
                .map(i -> new TransferItem(i.getTransferNo(), itemNo(i), i.getChecked() != null && i.getChecked() == 1)).toList();
        return new InventoryTransferDetail(new InventoryTransfer(e.getTransferNo(), e.getFromName(), e.getToName(), e.getFromType(),
                e.getFromRef(), e.getToType(), e.getToRef(), e.getItemType(), e.getPowerbankCount(), e.getStatus(), e.getOperatorNo(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString()), rows);
    }

    private static String itemNo(InvTransferItem i) {
        return i.getPowerbankNo() != null && !i.getPowerbankNo().isBlank() ? i.getPowerbankNo() : i.getCabinetNo();
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }

    private static AssetDiff vo(InvAssetDiff d) {
        return new AssetDiff(d.getDiffNo(), d.getSourceType(), d.getSourceRef(), d.getKind(), d.getItemType(), d.getItemNo(),
                d.getSiteNo(), d.getCabinetNo(), d.getExpectedQty(), d.getActualQty(), d.getStatus(), d.getResolveNote(),
                d.getResolvedBy(), d.getResolvedAt(), d.getCreatedAt());
    }
}
