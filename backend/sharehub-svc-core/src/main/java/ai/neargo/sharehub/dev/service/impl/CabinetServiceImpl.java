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

    public CabinetServiceImpl(CabinetMapper mapper) {
        this.mapper = mapper;
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
