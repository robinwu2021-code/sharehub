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
import ai.neargo.sharehub.dev.service.QcService;
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
    static final Set<String> DISPOSALS = Set.of("RETURN_SUPPLIER", "SCRAP");
    private static final int MAX_ITEMS = 200;

    private final InvIntakeMapper intakes;
    private final InvIntakeItemMapper items;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final QcService qc;
    private final WarehouseDirectory warehouses;

    public IntakeServiceImpl(InvIntakeMapper intakes, InvIntakeItemMapper items, CabinetMapper cabinets,
                             PowerbankMapper powerbanks, QcService qc, WarehouseDirectory warehouses) {
        this.intakes = intakes;
        this.items = items;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.qc = qc;
        this.warehouses = warehouses;
    }

    // —— 查询 ——

    @Override
    public PageResult<Intake> page(Integer page, Integer size, String keyword, String status, String warehouseNo) {
        LambdaQueryWrapper<InvIntake> w = new LambdaQueryWrapper<InvIntake>().orderByDesc(InvIntake::getId);
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
            return new IntakeItem(r.getItemType(), r.getItemNo(), sn, model, r.getQcNo(), r.getQcResult(),
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
        WarehouseItem wh = warehouses.list().stream().filter(x -> x.warehouseNo().equals(r.warehouseNo().trim())).findFirst()
                .orElseThrow(() -> BizException.notFound(r.warehouseNo()));
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
            else if (c.getIssuedAgentNo() != null) bad.add(no + "：在商家出库池");
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
        h.setKind("ARRIVAL");
        h.setWarehouseNo(wh.warehouseNo());
        h.setWarehouseName(wh.name());
        h.setSourceRef(blankToNull(r.sourceRef()));
        h.setArrivedAt(notBlank(r.arrivedAt()) ? LocalDateTime.parse(r.arrivedAt().trim().replace(' ', 'T')) : LocalDateTime.now());
        h.setStatus("OPEN");
        h.setNote(blankToNull(r.note()));
        h.setCreatedBy(operator());
        intakes.insert(h);
        cabNos.forEach(no -> insertItem(h.getIntakeNo(), CABINET, no));
        pbNos.forEach(no -> insertItem(h.getIntakeNo(), POWERBANK, no));
        log.info("到货登记 intakeNo={} warehouseNo={} 柜={} 宝={}", h.getIntakeNo(), wh.warehouseNo(), cabNos.size(), pbNos.size());
        return get(h.getIntakeNo());
    }

    // —— 质检 ——

    @Override
    @Transactional
    public IntakeDetail inspect(String intakeNo, String itemType, String itemNo, QcReq req) {
        InvIntake h = requireOpen(intakeNo);
        InvIntakeItem it = requireItem(intakeNo, itemType, itemNo);
        if (!PENDING.equals(it.getDisposition())) throw BizException.conflict("error.intake.item_handled", itemNo);
        QcRecord rec = CABINET.equals(it.getItemType()) ? qc.inspectCabinet(itemNo, req) : qc.inspectPowerbank(itemNo, req);
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
            // 条件更新：设备须仍在库、未绑定、未出库 —— 质检后被人拿去出库了，这里要拦住而不是把它写回仓里
            int n = CABINET.equals(i.getItemType())
                    ? cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, i.getItemNo())
                    .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNull(DevCabinet::getLocationNo)
                    .isNull(DevCabinet::getIssuedAgentNo).set(DevCabinet::getWarehouseNo, h.getWarehouseNo()))
                    : powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, i.getItemNo())
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNull(DevPowerbank::getCabinetNo)
                    .set(DevPowerbank::getWarehouseNo, h.getWarehouseNo()));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
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
        picked.forEach(i -> mark(i, req.disposition(), req.note().trim(), me, now));
        closeIfDone(h);
        log.info("待检处置 intakeNo={} disposition={} 台数={}", intakeNo, req.disposition(), picked.size());
        return get(intakeNo);
    }

    // —— 内部 ——

    private void insertItem(String intakeNo, String type, String no) {
        InvIntakeItem i = new InvIntakeItem();
        i.setIntakeNo(intakeNo);
        i.setItemType(type);
        i.setItemNo(no);
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
                    .eq(InvIntake::getStatus, "OPEN").set(InvIntake::getStatus, "DONE"));
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
        return h;
    }

    private InvIntake requireOpen(String intakeNo) {
        InvIntake h = require(intakeNo);
        if (!"OPEN".equals(h.getStatus())) throw BizException.conflict("error.intake.closed", intakeNo);
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
