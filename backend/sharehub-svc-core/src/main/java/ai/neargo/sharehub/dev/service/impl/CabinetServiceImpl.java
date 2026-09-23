package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.PageResult;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CabinetDetail;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CommandResult;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Slot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 设备业务实现。数据过滤横切；权限在 Controller。仓位派生自 slotTotal/availableCount。 */
@Service
public class CabinetServiceImpl implements CabinetService {

    private final CabinetMapper mapper;
    private final ai.neargo.sharehub.api.platform.port.LocationQueryPort locationQuery;

    public CabinetServiceImpl(CabinetMapper mapper,
                              ai.neargo.sharehub.api.platform.port.LocationQueryPort locationQuery) {
        this.mapper = mapper;
        this.locationQuery = locationQuery;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Cabinet save(String cabinetNo, java.util.Map<String, Object> body) {
        java.util.Map<String, Object> in = body == null ? java.util.Map.of() : body;
        String no = cabinetNo != null && !cabinetNo.isBlank() ? cabinetNo : str(in.get("cabinetNo"));
        if (no == null || no.isBlank()) throw new IllegalArgumentException("机柜编号 cabinetNo 必填");

        DevCabinet e = mapper.selectOne(new LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, no).last("limit 1"));
        boolean creating = e == null;
        if (creating) {
            if (cabinetNo != null && !cabinetNo.isBlank()) {
                // 走 /cabinets/{no} 却查不到：是编辑一个不存在的柜子，而不是「顺手新建」。
                // 静默新建会把打错的编号变成一台真实设备。
                throw new IllegalArgumentException("机柜不存在: " + no);
            }
            e = new DevCabinet();
            e.setCabinetNo(no);
            e.setTenantId("MAIN");
            e.setDeviceType("POWERBANK");
            // 新建默认在库：还没上架就置 ONLINE 会让它出现在 C 端可借列表里
            e.setStatus("IN_STOCK");
            e.setOnlineStatus("OFFLINE");
        }
        if (in.containsKey("sn")) e.setSn(str(in.get("sn")));
        if (in.containsKey("vendorCode")) e.setVendorCode(str(in.get("vendorCode")));
        if (in.containsKey("model")) e.setModel(str(in.get("model")));
        if (in.containsKey("slotTotal")) e.setSlotTotal(intOf(in.get("slotTotal")));
        if (in.containsKey("status") && !creating) e.setStatus(str(in.get("status")));

        /*
         * 归属：**只认 locationNo，siteNo/agentNo 一律反查**（见接口注释）。
         * 调用方传来的 siteNo/agentNo 直接忽略 —— 接受它们就等于允许三者互相矛盾。
         */
        if (in.containsKey("locationNo")) {
            String locNo = str(in.get("locationNo"));
            if (locNo == null || locNo.isBlank()) {
                e.setLocationNo(null); e.setLocationName(null); e.setSiteNo(null); e.setAgentNo(null);
            } else {
                var own = locationQuery.ownershipOf(locNo);
                if (own == null) throw new IllegalArgumentException("点位不存在: " + locNo);
                e.setLocationNo(own.locationNo());
                e.setLocationName(own.locationName());
                e.setSiteNo(own.siteNo());
                e.setAgentNo(own.agentNo());
            }
        }
        if (creating) mapper.insert(e); else mapper.updateById(e);
        return toVO(e);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("仓位数 slotTotal 必须是整数，收到: " + v);
        }
    }

    @Override
    public PageResult<Cabinet> page(Integer page, Integer size, String keyword, String onlineStatus, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(DevCabinet::getCabinetNo, keyword).or().like(DevCabinet::getLocationName, keyword));
        }
        if (onlineStatus != null && !onlineStatus.isBlank()) w.eq(DevCabinet::getOnlineStatus, onlineStatus);
        if (status != null && !status.isBlank()) w.eq(DevCabinet::getStatus, status);
        w.orderByAsc(DevCabinet::getId);
        Page<DevCabinet> r = mapper.selectPage(new Page<>(p, s), w);
        List<Cabinet> rows = r.getRecords().stream().map(CabinetServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public List<Cabinet> bySite(String siteNo) {
        if (siteNo == null || siteNo.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<DevCabinet>()
                        .eq(DevCabinet::getSiteNo, siteNo)
                        .isNull(DevCabinet::getArchivedAt)
                        .orderByAsc(DevCabinet::getId))
                .stream().map(CabinetServiceImpl::toVO).toList();
    }

    @Override
    public CabinetDetail detail(String cabinetNo) {
        DevCabinet e = mapper.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo));
        if (e == null) throw new IllegalArgumentException("柜机不存在: " + cabinetNo);
        return new CabinetDetail(toVO(e), slotsOf(e));
    }

    @Override
    public CommandResult sendCommand(String cabinetNo, String type, Map<String, Object> params) {
        // 骨架：回执 commandId；真实链路经 access-gateway /internal/gw/commands（幂等下发）。
        return new CommandResult("CMD" + System.nanoTime());
    }

    /** 派生仓位（与内存种子同规则）。 */
    private static List<Slot> slotsOf(DevCabinet c) {
        int total = c.getSlotTotal() == null ? 8 : c.getSlotTotal();
        int avail = c.getAvailableCount() == null ? 0 : c.getAvailableCount();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            boolean filled = i < avail;
            String health = (i == total - 1 && "FAULT".equals(c.getStatus())) ? "FAULT" : "OK";
            slots.add(new Slot(i + 1, filled ? "PB" + c.getCabinetNo().substring(3) + (i + 1) : null,
                    filled ? 40 + (i * 13) % 60 : null, filled ? "LOCKED" : "UNLOCKED", health));
        }
        return slots;
    }

    private static Cabinet toVO(DevCabinet e) {
        return new Cabinet(e.getCabinetNo(), e.getSn(), e.getVendorCode(), e.getModel(),
                e.getLocationNo(), e.getLocationName(),
                e.getSlotTotal() == null ? 0 : e.getSlotTotal(),
                e.getAvailableCount() == null ? 0 : e.getAvailableCount(),
                e.getOnlineStatus(), e.getStatus(), e.getFwVersion(), e.getLastHeartbeatAt(),
                e.getSiteNo());
    }

    // ── 归档 / 取消归档（前端契约 Archivable）──
    // 本实现不走 AbstractCrudService（它有自己的业务规则），故在此实现同样语义：
    // archivedAt 时间戳，null=在用。**与 BaseEntity.deleted 是两回事**，见 Archivable。

    @Override
    @Transactional
    public Cabinet archive(String no) {
        return setArchived(no, java.time.LocalDateTime.now());
    }

    @Override
    @Transactional
    public Cabinet unarchive(String no) {
        return setArchived(no, null);
    }

    private Cabinet setArchived(String no, java.time.LocalDateTime at) {
        DevCabinet e = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, no).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("机柜不存在: " + no);
        e.setArchivedAt(at);
        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int importRows(java.util.List<java.util.Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        // **逐行校验、整批回滚**：一行有问题就整批拒绝并指出第几行。
        // 部分成功会让运营不知道该重传全部还是补传剩余，而重传已成功的行会撞唯一键。
        for (int i = 0; i < rows.size(); i++) {
            Object no = rows.get(i).get("cabinetNo");
            if (no == null || String.valueOf(no).isBlank()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行缺少 cabinetNo");
            }
        }
        int n = 0;
        for (java.util.Map<String, Object> r : rows) {
            DevCabinet e = new DevCabinet();
            e.setCabinetNo(String.valueOf(r.get("cabinetNo")));
            e.setTenantId("MAIN");
            if (r.get("siteNo") != null) e.setSiteNo(String.valueOf(r.get("siteNo")));
            if (r.get("locationNo") != null) e.setLocationNo(String.valueOf(r.get("locationNo")));
            if (r.get("vendorCode") != null) e.setVendorCode(String.valueOf(r.get("vendorCode")));
            e.setDeviceType("POWERBANK");
            e.setStatus("IN_STOCK");
            mapper.insert(e);
            n++;
        }
        return n;
    }
}
