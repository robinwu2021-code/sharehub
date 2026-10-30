package ai.neargo.sharehub.inv.service.impl;

import static ai.neargo.sharehub.inv.remote.InventoryCalls.call;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.DisposalReason;
import ai.neargo.sharehub.inv.MaterialCategory;
import ai.neargo.sharehub.inv.MaterialDocKind;
import ai.neargo.sharehub.inv.MaterialDocPurpose;
import ai.neargo.sharehub.inv.MaterialTracking;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.dto.MaterialDtos.Material;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialBalance;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDoc;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDocLine;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDocReq;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialMinQtyReq;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialReq;
import ai.neargo.sharehub.inv.entity.InvMaterial;
import ai.neargo.sharehub.inv.entity.InvMaterialDoc;
import ai.neargo.sharehub.inv.entity.InvMaterialDocLine;
import ai.neargo.sharehub.inv.entity.InvMaterialThreshold;
import ai.neargo.sharehub.inv.mapper.InvMaterialDocLineMapper;
import ai.neargo.sharehub.inv.mapper.InvMaterialDocMapper;
import ai.neargo.sharehub.inv.mapper.InvMaterialMapper;
import ai.neargo.sharehub.inv.mapper.InvMaterialThresholdMapper;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.InventoryOperationJournal;
import ai.neargo.sharehub.inv.service.InventoryOperationJournal.Operation;
import ai.neargo.sharehub.inv.service.MaterialService;
import ai.neargo.sharehub.inv.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 库存管理 · 物料（V210），口径见 {@link MaterialService}。 */
@Service
public class MaterialServiceImpl implements MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialServiceImpl.class);
    private static final int MAX_LINES = 100;
    private static final int MAX_QTY = 1_000_000;

    private final InvMaterialMapper materials;
    private final InvMaterialDocMapper docs;
    private final InvMaterialDocLineMapper lines;
    private final InvMaterialThresholdMapper thresholds;
    private final WarehouseDirectory warehouses;
    private final SupplierService suppliers;
    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final SysParamPort params;
    private final InventoryOperationJournal journal;

    public MaterialServiceImpl(InvMaterialMapper materials, InvMaterialDocMapper docs, InvMaterialDocLineMapper lines,
                               InvMaterialThresholdMapper thresholds, WarehouseDirectory warehouses, SupplierService suppliers,
                               RemoteInventoryPort remote, InventoryLinks links, SysParamPort params, InventoryOperationJournal journal) {
        this.materials = materials;
        this.docs = docs;
        this.lines = lines;
        this.thresholds = thresholds;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.remote = remote;
        this.links = links;
        this.params = params;
        this.journal = journal;
    }

    // —— 物料档案 ——

    @Override
    public PageResult<Material> page(Integer page, Integer size, String keyword, String category, Boolean showArchived) {
        LambdaQueryWrapper<InvMaterial> w = new LambdaQueryWrapper<InvMaterial>().orderByAsc(InvMaterial::getMaterialNo);
        if (!Boolean.TRUE.equals(showArchived)) w.isNull(InvMaterial::getArchivedAt);
        if (notBlank(category)) w.eq(InvMaterial::getCategory, category.trim());
        if (notBlank(keyword)) {
            String k = keyword.trim();
            w.and(x -> x.like(InvMaterial::getMaterialNo, k).or().like(InvMaterial::getName, k).or().like(InvMaterial::getSpec, k));
        }
        Page<InvMaterial> p = materials.selectPage(new Page<>(page == null || page < 1 ? 1 : page,
                size == null || size < 1 ? 20 : Math.min(size, 500)), w);
        return new PageResult<>(p.getRecords().stream().map(this::vo).toList(), p.getTotal());
    }

    @Override
    @Transactional
    public Material create(MaterialReq r) {
        InvMaterial m = new InvMaterial();
        m.setMaterialNo(IdGenerator.next("MT"));
        m.setTenantId("MAIN");
        apply(m, r);
        materials.insert(m);
        log.info("物料新建 materialNo={} name={} tracking={}", m.getMaterialNo(), m.getName(), m.getTrackingMode());
        return vo(m);
    }

    @Override
    @Transactional
    public Material update(String materialNo, MaterialReq r) {
        journal.requireMasterIdle(materialNo, "$.lines[*].materialNo");
        InvMaterial m = require(materialNo);
        if (m.getDeviceType() != null) throw BizException.conflict("error.material.builtin", materialNo);
        String tracking = m.getTrackingMode();
        String uom = m.getUom();
        apply(m, r);
        // 有流水后跟踪方式与单位不可改（库存服务同样拒：trackingMode / baseUom 定了就不能变）
        if (moved(materialNo) && (!tracking.equals(m.getTrackingMode()) || !uom.equals(m.getUom())))
            throw BizException.conflict("error.material.locked", materialNo);
        materials.update(null, new LambdaUpdateWrapper<InvMaterial>().eq(InvMaterial::getMaterialNo, materialNo)
                .set(InvMaterial::getName, m.getName()).set(InvMaterial::getCategory, m.getCategory())
                .set(InvMaterial::getTrackingMode, m.getTrackingMode()).set(InvMaterial::getUom, m.getUom())
                .set(InvMaterial::getSpec, m.getSpec()).set(InvMaterial::getVendorCode, m.getVendorCode())
                .set(InvMaterial::getDefaultSupplierNo, m.getDefaultSupplierNo()));
        // 已同步过的物料改名要同步过去（跟踪方式未变，库存服务接受）
        if (links.get("mat:" + materialNo) != null && service()) call(() -> syncItem(m));
        return vo(m);
    }

    @Override
    @Transactional
    public Material archive(String materialNo) {
        journal.requireMasterIdle(materialNo, "$.lines[*].materialNo");
        InvMaterial m = require(materialNo);
        if (m.getDeviceType() != null) throw BizException.conflict("error.material.builtin", materialNo);
        if (m.getArchivedAt() != null) return vo(m);
        String itemId = links.get("mat:" + materialNo);
        if (itemId != null && service()) {
            List<String> held = call(() -> remote.api().balances(null, itemId)).stream()
                    .filter(b -> b.onHand() != null && b.onHand().signum() > 0).map(b -> links.warehouseNoOf(b.locationId())).toList();
            if (!held.isEmpty()) throw BizException.conflict("error.material.in_stock", String.join("、", held));
        }
        materials.update(null, new LambdaUpdateWrapper<InvMaterial>().eq(InvMaterial::getMaterialNo, materialNo)
                .set(InvMaterial::getArchivedAt, LocalDateTime.now()));
        log.info("物料归档 materialNo={}", materialNo);
        return vo(require(materialNo));
    }

    @Override
    @Transactional
    public Material unarchive(String materialNo) {
        require(materialNo);
        materials.update(null, new LambdaUpdateWrapper<InvMaterial>().eq(InvMaterial::getMaterialNo, materialNo)
                .set(InvMaterial::getArchivedAt, null));
        return vo(require(materialNo));
    }

    private void apply(InvMaterial m, MaterialReq r) {
        if (r == null || !notBlank(r.name())) throw BizException.badRequest("error.common.missing_parameter", "name");
        m.setName(r.name().trim());
        m.setCategory(enumOf(MaterialCategory.values(), r.category(), "category").name());
        m.setTrackingMode(enumOf(MaterialTracking.values(), r.trackingMode(), "trackingMode").name());
        if (!notBlank(r.uom()) || r.uom().trim().length() > 16) throw BizException.badRequest("error.common.invalid_value", "uom");
        m.setUom(r.uom().trim());
        m.setSpec(trim(r.spec()));
        m.setVendorCode(trim(r.vendorCode()));
        String sp = trim(r.defaultSupplierNo());
        if (sp != null && suppliers.activeName(sp) == null) throw BizException.notFound(sp);
        m.setDefaultSupplierNo(sp);
    }

    // —— 物料单据 ——

    @Override
    public PageResult<MaterialDoc> docs(Integer page, Integer size, String keyword, String kind, String purpose, String warehouseNo,
                                        String materialNo) {
        LambdaQueryWrapper<InvMaterialDoc> w = new LambdaQueryWrapper<InvMaterialDoc>().orderByDesc(InvMaterialDoc::getId);
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible != null) {
            if (visible.isEmpty()) return new PageResult<>(List.of(), 0);
            w.and(q -> q.in(InvMaterialDoc::getWarehouseNo, visible).or().in(InvMaterialDoc::getToWarehouseNo, visible));
        }
        if (notBlank(kind)) w.eq(InvMaterialDoc::getKind, kind.trim());
        if (notBlank(purpose)) w.eq(InvMaterialDoc::getPurpose, purpose.trim());
        if (notBlank(warehouseNo)) {
            String wh = warehouseNo.trim();
            w.and(x -> x.eq(InvMaterialDoc::getWarehouseNo, wh).or().eq(InvMaterialDoc::getToWarehouseNo, wh));
        }
        if (notBlank(materialNo)) {
            List<String> nos = lines.selectList(new LambdaQueryWrapper<InvMaterialDocLine>().eq(InvMaterialDocLine::getMaterialNo, materialNo.trim()))
                    .stream().map(InvMaterialDocLine::getDocNo).distinct().toList();
            if (nos.isEmpty()) return new PageResult<>(List.of(), 0);
            w.in(InvMaterialDoc::getDocNo, nos);
        }
        if (notBlank(keyword)) {
            String k = keyword.trim();
            List<String> bySerial = lines.selectList(new LambdaQueryWrapper<InvMaterialDocLine>().like(InvMaterialDocLine::getSerialNos, k))
                    .stream().map(InvMaterialDocLine::getDocNo).distinct().toList();
            w.and(x -> {
                x.like(InvMaterialDoc::getDocNo, k).or().like(InvMaterialDoc::getNote, k).or().like(InvMaterialDoc::getWoNo, k)
                        .or().like(InvMaterialDoc::getReceiverNo, k);
                if (!bySerial.isEmpty()) x.or().in(InvMaterialDoc::getDocNo, bySerial);
            });
        }
        Page<InvMaterialDoc> p = docs.selectPage(new Page<>(page == null || page < 1 ? 1 : page,
                size == null || size < 1 ? 20 : Math.min(size, 200)), w);
        Map<String, List<InvMaterialDocLine>> byDoc = p.getRecords().isEmpty() ? Map.of()
                : lines.selectList(new LambdaQueryWrapper<InvMaterialDocLine>()
                        .in(InvMaterialDocLine::getDocNo, p.getRecords().stream().map(InvMaterialDoc::getDocNo).toList())
                        .orderByAsc(InvMaterialDocLine::getLineNo))
                .stream().collect(Collectors.groupingBy(InvMaterialDocLine::getDocNo));
        return new PageResult<>(p.getRecords().stream().map(d -> vo(d, byDoc.getOrDefault(d.getDocNo(), List.of()))).toList(), p.getTotal());
    }

    @Override
    public MaterialDoc doc(String docNo) {
        InvMaterialDoc d = docNo == null ? null
                : docs.selectOne(new LambdaQueryWrapper<InvMaterialDoc>().eq(InvMaterialDoc::getDocNo, docNo).last("limit 1"));
        if (d == null) throw BizException.notFound(docNo);
        warehouses.requireAnyVisible(d.getWarehouseNo(), d.getToWarehouseNo());
        return vo(d, lines.selectList(new LambdaQueryWrapper<InvMaterialDocLine>().eq(InvMaterialDocLine::getDocNo, docNo)
                .orderByAsc(InvMaterialDocLine::getLineNo)));
    }

    /** 校验通过的一行。 */
    private record Line(InvMaterial material, int qty, List<String> serials, BigDecimal unitCost) {
        boolean serial() {
            return MaterialTracking.SERIAL.name().equals(material.getTrackingMode());
        }
    }

    @Override
    public MaterialDoc createDoc(MaterialDocReq r) {
        return journal.execute(() -> postDoc(r));
    }

    private MaterialDoc postDoc(MaterialDocReq r) {
        if (r == null || !notBlank(r.purpose())) throw BizException.badRequest("error.common.missing_parameter", "purpose");
        String key = notBlank(r.requestKey()) ? r.requestKey().trim() : IdGenerator.next("MR");
        if (key.length() > 128) throw BizException.badRequest("error.common.invalid_value", "requestKey");
        String payload = journal.payload(r);
        Operation existing = journal.find("MATERIAL_DOC", key, payload);
        if (existing == null) requireService();
        else {
            // 旧操作固定SERVICE，不随全局引擎切换改走LOCAL；已完成的请求直接回放本地结果。
            if ("COMPLETED".equals(existing.phase())) return doc(existing.bizRef());
            ai.neargo.sharehub.inv.remote.InventoryCalls.requireConfigured(remote.api());
        }
        MaterialDocPurpose purpose = enumOf(MaterialDocPurpose.values(), r.purpose(), "purpose");
        MaterialDocKind kind = purpose.kind;
        if (!notBlank(r.note())) throw BizException.badRequest("error.common.note_required");
        if (r.lines() == null || r.lines().isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "lines");
        if (r.lines().size() > MAX_LINES) throw BizException.badRequest("error.common.invalid_value", "lines > " + MAX_LINES);
        WarehouseItem wh = liveWarehouse(r.warehouseNo());
        WarehouseItem to = null;
        if (kind == MaterialDocKind.TRANSFER) {
            to = liveWarehouse(r.toWarehouseNo());
            if (to.warehouseNo().equals(wh.warehouseNo())) throw BizException.badRequest("error.common.invalid_value", "toWarehouseNo");
        }
        String supplierName = null;
        if (purpose == MaterialDocPurpose.PURCHASE || purpose == MaterialDocPurpose.RETURN_SUPPLIER) {
            if (!notBlank(r.supplierNo())) throw BizException.badRequest("error.common.missing_parameter", "supplierNo");
            supplierName = suppliers.activeName(r.supplierNo().trim());
            if (supplierName == null) throw BizException.notFound(r.supplierNo());
        }
        String reason = null;
        if (purpose == MaterialDocPurpose.SCRAP) reason = enumOf(DisposalReason.values(), r.reasonCode(), "reasonCode").name();
        if (purpose == MaterialDocPurpose.REQUISITION && !notBlank(r.receiverNo()) && !notBlank(r.woNo()))
            throw BizException.badRequest("error.common.missing_parameter", "receiverNo / woNo");
        boolean inbound = kind == MaterialDocKind.INBOUND;
        boolean priced = inbound && r.lines().stream().anyMatch(l -> l.unitCost() != null);
        if (r.lines().stream().anyMatch(l -> l.unitCost() != null && l.unitCost().signum() < 0))
            throw BizException.badRequest("error.common.invalid_value", "unitCost");
        if (priced && !notBlank(r.currency())) throw BizException.badRequest("error.common.missing_parameter", "currency");

        // 逐行校验，不符的一次列全（M2 ~ M4）；数量够不够、序列号在不在以库存服务为准，这里先按它的余额预检一遍给出可读的清单
        Map<String, InvMaterial> mats = materials.selectList(new LambdaQueryWrapper<InvMaterial>().in(InvMaterial::getMaterialNo,
                        r.lines().stream().map(MaterialDocLine::materialNo).filter(Objects::nonNull).distinct().toList()))
                .stream().collect(Collectors.toMap(InvMaterial::getMaterialNo, Function.identity()));
        List<String> bad = new ArrayList<>();
        List<Line> ok = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (MaterialDocLine l : r.lines()) {
            InvMaterial m = l.materialNo() == null ? null : mats.get(l.materialNo().trim());
            if (m == null) { bad.add(l.materialNo() + "：物料不存在"); continue; }
            if (m.getDeviceType() != null) { bad.add(m.getName() + "：机柜与充电宝走设备流程（入库单 / 出库单 / 调拨）"); continue; }
            if (m.getArchivedAt() != null && kind != MaterialDocKind.OUTBOUND) { bad.add(m.getName() + "：已归档"); continue; }
            if (MaterialTracking.SERIAL.name().equals(m.getTrackingMode())) {
                List<String> sns = l.serialNos() == null ? List.of()
                        : l.serialNos().stream().filter(MaterialServiceImpl::notBlank).map(String::trim).distinct().toList();
                if (sns.isEmpty()) { bad.add(m.getName() + "：逐件物料须录入序列号"); continue; }
                for (String sn : sns) if (!seen.add(m.getMaterialNo() + ":" + sn)) bad.add(m.getName() + " " + sn + "：同一张单重复");
                ok.add(new Line(m, sns.size(), sns, inbound ? l.unitCost() : null));
            } else {
                if (l.qty() == null || l.qty() <= 0 || l.qty() > MAX_QTY) { bad.add(m.getName() + "：数量须为正整数"); continue; }
                ok.add(new Line(m, l.qty(), List.of(), inbound ? l.unitCost() : null));
            }
        }
        if (!bad.isEmpty()) throw BizException.conflict("error.material.lines_rejected", String.join("；", bad));

        Operation operation = journal.lock("MATERIAL_DOC", key, payload, "SERVICE", "MD");
        if ("COMPLETED".equals(operation.phase())) return doc(operation.bizRef());
        // 已发送出库过账的请求可能已扣库，恢复时不再用现余额预检同一批数量。
        if (!inbound && !journal.attempted(operation, "OUT_POST")) bad.addAll(journal.reference(() -> shortages(wh, ok)));
        if (!bad.isEmpty()) throw BizException.conflict("error.material.lines_rejected", String.join("；", bad));
        String docNo = operation.bizRef();
        InventoryApi api = remote.api();
        String remark = (purpose.name() + " " + docNo + "：" + r.note().trim());
        final WarehouseItem toWh = to;
        final String rsn = reason;
        String[] remoteNos = call(() -> {
            String loc = journal.reference(() -> links.locationId(api, wh.warehouseNo()));
            Map<String, String> itemIds = new HashMap<>();
            for (Line l : ok) itemIds.computeIfAbsent(l.material().getMaterialNo(), k -> journal.reference(() -> syncItem(l.material())));
            if (inbound) return new String[]{null, inboundDoc(api, loc, purpose.name(), docNo, remark, ok, itemIds, operation)};
            Map<String, String> targets = Map.of();
            if (purpose == MaterialDocPurpose.RETURN_SUPPLIER) {
                api.upsertTarget("SUPPLIER", r.supplierNo().trim(), suppliers.activeName(r.supplierNo().trim()), null);
                targets = Map.of("SUPPLIER", r.supplierNo().trim());
            }
            String outPurpose = purpose == MaterialDocPurpose.TRANSFER ? "TRANSFER_OUT" : purpose.name();
            final Map<String, String> outboundTargets = targets;
            String out = journal.step(operation, "OUT_CREATE", () -> api.createOutbound(loc, outPurpose, rsn, outboundTargets, remark,
                    ok.stream().filter(l -> !l.serial()).map(l -> new InventoryApi.OutboundLine(itemIds.get(l.material().getMaterialNo()), l.qty(), null)).toList(),
                    ok.stream().filter(Line::serial).flatMap(l -> l.serials().stream()).toList(), journal.stepKey(operation, "OUT_CREATE")));
            journal.step(operation, "OUT_POST", () -> { api.postOutbound(out, journal.stepKey(operation, "OUT_POST")); return out; });
            if (toWh == null) return new String[]{out, null};
            return new String[]{out, inboundDoc(api, journal.reference(() -> links.locationId(api, toWh.warehouseNo())), "TRANSFER_IN", docNo, remark, ok, itemIds, operation)};
        });

        InvMaterialDoc d = new InvMaterialDoc();
        d.setDocNo(docNo);
        d.setTenantId("MAIN");
        d.setKind(kind.name());
        d.setPurpose(purpose.name());
        d.setWarehouseNo(wh.warehouseNo());
        d.setWarehouseName(wh.name());
        d.setToWarehouseNo(to == null ? null : to.warehouseNo());
        d.setToWarehouseName(to == null ? null : to.name());
        d.setSupplierNo(supplierName == null ? null : r.supplierNo().trim());
        d.setSupplierName(supplierName);
        d.setReasonCode(reason);
        d.setReceiverNo(purpose == MaterialDocPurpose.REQUISITION ? trim(r.receiverNo()) : null);
        d.setWoNo(purpose == MaterialDocPurpose.REQUISITION ? trim(r.woNo()) : null);
        d.setCurrency(priced ? r.currency().trim().toUpperCase() : null);
        d.setNote(r.note().trim());
        d.setTotalQty(ok.stream().mapToInt(Line::qty).sum());
        d.setRemoteOutNo(remoteNos[0]);
        d.setRemoteInNo(remoteNos[1]);
        d.setOperationNo(operation.operationNo());
        d.setCreatedBy(operator());
        docs.insert(d);
        int n = 0;
        for (Line l : ok) {
            InvMaterialDocLine x = new InvMaterialDocLine();
            x.setDocNo(docNo);
            x.setLineNo(++n);
            x.setMaterialNo(l.material().getMaterialNo());
            x.setMaterialName(l.material().getName());
            x.setQty(l.qty());
            x.setSerialNos(l.serials().isEmpty() ? null : String.join(",", l.serials()));
            x.setUnitCost(l.unitCost());
            lines.insert(x);
        }
        log.info("物料单据过账 docNo={} purpose={} warehouseNo={} toWarehouseNo={} 行={} 数量={} remoteOut={} remoteIn={}", docNo, purpose,
                wh.warehouseNo(), d.getToWarehouseNo(), ok.size(), d.getTotalQty(), remoteNos[0], remoteNos[1]);
        journal.complete(operation);
        return doc(docNo);
    }

    private String inboundDoc(InventoryApi api, String loc, String purpose, String docNo, String remark, List<Line> ok,
                              Map<String, String> itemIds, Operation operation) {
        List<InventoryApi.InboundLine> in = ok.stream().map(l -> l.serial()
                ? new InventoryApi.InboundLine(itemIds.get(l.material().getMaterialNo()), null, null,
                        l.serials().stream().map(sn -> new InventoryApi.Serial(sn, null, null)).toList())
                : new InventoryApi.InboundLine(itemIds.get(l.material().getMaterialNo()), l.qty(), null, List.of())).toList();
        String no = journal.step(operation, "IN_CREATE", () -> api.createInbound(loc, purpose, docNo, remark, in, journal.stepKey(operation, "IN_CREATE")));
        journal.step(operation, "IN_POST", () -> { api.postInbound(no, journal.stepKey(operation, "IN_POST")); return no; });
        return no;
    }

    /** 出库预检：数量物料在手不够、逐件物料序列号不在本仓的，按行列出。 */
    private List<String> shortages(WarehouseItem wh, List<Line> ok) {
        InventoryApi api = remote.api();
        return call(() -> {
            String loc = links.locationId(api, wh.warehouseNo());
            List<String> out = new ArrayList<>();
            Map<String, Integer> want = new LinkedHashMap<>();
            for (Line l : ok) if (!l.serial()) want.merge(l.material().getMaterialNo(), l.qty(), Integer::sum);
            for (var e : want.entrySet()) {
                String itemId = links.get("mat:" + e.getKey());
                int have = itemId == null ? 0 : api.balances(loc, itemId).stream().map(InventoryApi.Balance::onHand)
                        .filter(Objects::nonNull).mapToInt(BigDecimal::intValue).sum();
                if (e.getValue() > have) out.add(nameOf(ok, e.getKey()) + "：" + wh.name() + " 在手 " + have + "，不够出 " + e.getValue());
            }
            for (Line l : ok) {
                if (!l.serial()) continue;
                String itemId = links.get("mat:" + l.material().getMaterialNo());
                List<String> here = itemId == null ? List.of() : api.serials(itemId, loc).stream().map(InventoryApi.InStock::serialNo).toList();
                for (String sn : l.serials()) if (!here.contains(sn)) out.add(l.material().getName() + " " + sn + "：不在 " + wh.name());
            }
            return out;
        });
    }

    // —— 物料库存 ——

    @Override
    public List<MaterialBalance> balances(String warehouseNo, String materialNo, String category, Boolean lowOnly) {
        requireService();
        List<WarehouseItem> whs = warehouses.list().stream().filter(w -> w.archivedAt() == null)
                .filter(w -> !notBlank(warehouseNo) || w.warehouseNo().equals(warehouseNo.trim())).toList();
        LambdaQueryWrapper<InvMaterial> mq = new LambdaQueryWrapper<InvMaterial>().isNull(InvMaterial::getDeviceType);
        if (notBlank(materialNo)) mq.eq(InvMaterial::getMaterialNo, materialNo.trim());
        if (notBlank(category)) mq.eq(InvMaterial::getCategory, category.trim());
        List<InvMaterial> mats = materials.selectList(mq);
        if (whs.isEmpty() || mats.isEmpty()) return List.of();
        Map<String, Integer> min = thresholds.selectList(new LambdaQueryWrapper<InvMaterialThreshold>()).stream()
                .collect(Collectors.toMap(t -> t.getWarehouseNo() + ":" + t.getMaterialNo(), InvMaterialThreshold::getMinQty, (a, b) -> a));
        InventoryApi api = remote.api();
        return call(() -> {
            Map<String, Integer> onHand = new HashMap<>();
            for (InventoryApi.Balance b : api.balances(null, null)) {
                String mat = materialNoOf(b.itemId());
                if (mat == null || b.onHand() == null) continue;
                onHand.merge(links.warehouseNoOf(b.locationId()) + ":" + mat, b.onHand().intValue(), Integer::sum);
            }
            List<MaterialBalance> out = new ArrayList<>();
            for (WarehouseItem w : whs) {
                for (InvMaterial m : mats) {
                    String k = w.warehouseNo() + ":" + m.getMaterialNo();
                    if (!onHand.containsKey(k) && !min.containsKey(k)) continue;
                    int qty = onHand.getOrDefault(k, 0);
                    int lim = min.getOrDefault(k, 0);
                    boolean low = lim > 0 && qty < lim;
                    if (Boolean.TRUE.equals(lowOnly) && !low) continue;
                    List<String> sns = List.of();
                    String itemId = links.get("mat:" + m.getMaterialNo());
                    if (MaterialTracking.SERIAL.name().equals(m.getTrackingMode()) && itemId != null && qty > 0)
                        sns = api.serials(itemId, links.locationId(api, w.warehouseNo())).stream().map(InventoryApi.InStock::serialNo).sorted().toList();
                    out.add(new MaterialBalance(w.warehouseNo(), w.name(), m.getMaterialNo(), m.getName(), m.getCategory(),
                            m.getTrackingMode(), m.getUom(), qty, lim, low, sns));
                }
            }
            return out;
        });
    }

    @Override
    @Transactional
    public MaterialBalance saveMinQty(MaterialMinQtyReq r) {
        if (r == null || r.minQty() == null || r.minQty() < 0 || r.minQty() > MAX_QTY) throw BizException.badRequest("error.common.invalid_value", "minQty");
        InvMaterial m = require(r.materialNo());
        if (m.getDeviceType() != null) throw BizException.conflict("error.material.builtin", m.getMaterialNo());
        WarehouseItem w = liveWarehouse(r.warehouseNo());
        InvMaterialThreshold t = thresholds.selectOne(new LambdaQueryWrapper<InvMaterialThreshold>()
                .eq(InvMaterialThreshold::getWarehouseNo, w.warehouseNo()).eq(InvMaterialThreshold::getMaterialNo, m.getMaterialNo()).last("limit 1"));
        if (t == null) {
            t = new InvMaterialThreshold();
            t.setTenantId("MAIN");
            t.setWarehouseNo(w.warehouseNo());
            t.setMaterialNo(m.getMaterialNo());
            t.setMinQty(r.minQty());
            thresholds.insert(t);
        } else {
            t.setMinQty(r.minQty());
            thresholds.updateById(t);
        }
        log.info("物料安全库存 warehouseNo={} materialNo={} minQty={}", w.warehouseNo(), m.getMaterialNo(), r.minQty());
        if (!service()) return new MaterialBalance(w.warehouseNo(), w.name(), m.getMaterialNo(), m.getName(), m.getCategory(),
                m.getTrackingMode(), m.getUom(), 0, r.minQty(), false, List.of());
        return balances(w.warehouseNo(), m.getMaterialNo(), null, false).stream().findFirst()
                .orElse(new MaterialBalance(w.warehouseNo(), w.name(), m.getMaterialNo(), m.getName(), m.getCategory(),
                        m.getTrackingMode(), m.getUom(), 0, r.minQty(), r.minQty() > 0, List.of()));
    }

    @Override
    public List<MaterialBalance> lowRows() {
        if (!service() || !remote.api().configured()) return List.of();
        if (thresholds.selectCount(new LambdaQueryWrapper<InvMaterialThreshold>().gt(InvMaterialThreshold::getMinQty, 0)) == 0) return List.of();
        try {
            return DataScopeContext.executeWithoutScope(() -> balances(null, null, null, true));
        } catch (BizException e) {
            // 告警判定每分钟跑：库存服务一时不可用不该让整轮判定失败，下一轮再看
            log.warn("物料低库存判定跳过：库存服务不可用 code={}，下一轮重试", e.getMessage());
            return List.of();
        }
    }

    // —— 内部 ——

    /** 物料 → 库存服务 itemId：按物料号认领 / 新建，免检（gatePolicy NONE —— 质检只管柜和宝）。 */
    private String syncItem(InvMaterial m) {
        String id = remote.api().upsertItem(m.getMaterialNo(), new InventoryApi.ItemSpec(m.getName(), m.getUom(), m.getTrackingMode(),
                "NONE", null, null));
        links.put("mat:" + m.getMaterialNo(), id);
        return id;
    }

    /** itemId → 物料号（只认物料映射 mat:，设备的 item: 映射不在物料库存里）。 */
    private String materialNoOf(String itemId) {
        return links.listByPrefix("mat:").stream().filter(s -> itemId != null && itemId.equals(s.getV())).findFirst()
                .map(s -> s.getK().substring(4)).orElse(null);
    }

    private boolean moved(String materialNo) {
        return lines.selectCount(new LambdaQueryWrapper<InvMaterialDocLine>().eq(InvMaterialDocLine::getMaterialNo, materialNo)) > 0;
    }

    private Material vo(InvMaterial m) {
        return new Material(m.getMaterialNo(), m.getName(), m.getCategory(), m.getTrackingMode(), m.getUom(), m.getSpec(),
                m.getVendorCode(), m.getDefaultSupplierNo(), m.getDeviceType() != null,
                m.getDeviceType() != null || moved(m.getMaterialNo()), m.getArchivedAt(), m.getCreatedAt());
    }

    private static MaterialDoc vo(InvMaterialDoc d, List<InvMaterialDocLine> ls) {
        return new MaterialDoc(d.getDocNo(), d.getKind(), d.getPurpose(), d.getWarehouseNo(), d.getWarehouseName(), d.getToWarehouseNo(),
                d.getToWarehouseName(), d.getSupplierNo(), d.getSupplierName(), d.getReasonCode(), d.getReceiverNo(), d.getWoNo(),
                d.getCurrency(), d.getNote(), d.getTotalQty() == null ? 0 : d.getTotalQty(),
                ls.stream().map(l -> new MaterialDocLine(l.getMaterialNo(), l.getMaterialName(), l.getQty(),
                        l.getSerialNos() == null ? null : Arrays.asList(l.getSerialNos().split(",")), l.getUnitCost())).toList(),
                d.getCreatedBy(), d.getCreatedAt());
    }

    private InvMaterial require(String no) {
        InvMaterial m = no == null ? null
                : materials.selectOne(new LambdaQueryWrapper<InvMaterial>().eq(InvMaterial::getMaterialNo, no.trim()).last("limit 1"));
        if (m == null) throw BizException.notFound(no);
        return m;
    }

    private WarehouseItem liveWarehouse(String no) {
        if (!notBlank(no)) throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        warehouses.requireVisible(no.trim());
        return warehouses.list().stream().filter(w -> w.warehouseNo().equals(no.trim()) && w.archivedAt() == null).findFirst()
                .orElseThrow(() -> BizException.notFound(no));
    }

    private static String nameOf(List<Line> ok, String materialNo) {
        return ok.stream().filter(l -> l.material().getMaterialNo().equals(materialNo)).map(l -> l.material().getName()).findFirst().orElse(materialNo);
    }

    private boolean service() {
        return "SERVICE".equalsIgnoreCase(params.textOf("inv.engine", "LOCAL"));
    }

    private void requireService() {
        if (!service()) throw BizException.conflict("error.inventory.material_service_only");
        ai.neargo.sharehub.inv.remote.InventoryCalls.requireConfigured(remote.api());
    }

    private static <E extends Enum<E>> E enumOf(E[] values, String v, String field) {
        return Arrays.stream(values).filter(x -> x.name().equals(v == null ? null : v.trim())).findFirst()
                .orElseThrow(() -> BizException.badRequest("error.common.invalid_value", field));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }
}
