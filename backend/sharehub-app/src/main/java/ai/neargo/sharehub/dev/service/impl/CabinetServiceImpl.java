package ai.neargo.powerbank.dev.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dev.entity.DevCabinet;
import ai.neargo.powerbank.dev.mapper.CabinetMapper;
import ai.neargo.powerbank.dev.service.CabinetService;
import ai.neargo.powerbank.dto.Dto.Cabinet;
import ai.neargo.powerbank.dto.Dto.CabinetDetail;
import ai.neargo.powerbank.dto.Dto.CommandResult;
import ai.neargo.powerbank.dto.Dto.Slot;
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
                e.getOnlineStatus(), e.getStatus(), e.getFwVersion(), e.getLastHeartbeatAt());
    }
}
