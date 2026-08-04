package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.CabinetMonitorRow;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;
import ai.neargo.sharehub.dev.entity.DevShadow;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import ai.neargo.sharehub.dev.mapper.ShadowMapper;
import ai.neargo.sharehub.dev.service.MonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实时监控实现：以 {@code dev_shadow} 为驱动表分页，再按 {@code cabinet_no} 回填
 * {@code dev_cabinet} 的点位名（读模型 {@code dev_cabinet ⋈ dev_shadow}）。
 *
 * <p>驱动表选影子而非机柜，是因为监控页看的是「有遥测的设备当前怎么样」；
 * 从未上报过的机柜没有影子行，也就没有 online/signal/temp 可看。
 */
@Service
public class MonitorServiceImpl implements MonitorService {

    private final ShadowMapper shadowMapper;
    private final TelemetryQueryPort telemetry;
    private final CabinetMapper cabinetMapper;

    public MonitorServiceImpl(ShadowMapper shadowMapper, TelemetryQueryPort telemetry,
                              CabinetMapper cabinetMapper) {
        this.shadowMapper = shadowMapper;
        this.telemetry = telemetry;
        this.cabinetMapper = cabinetMapper;
    }

    @Override
    public PageResult<CabinetMonitorRow> monitor(Integer page, Integer size, String keyword, Boolean online) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<DevShadow> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) w.like(DevShadow::getCabinetNo, keyword);
        if (online != null) w.eq(DevShadow::getOnline, online ? 1 : 0);
        w.orderByDesc(DevShadow::getSnapshotAt);

        Page<DevShadow> r = shadowMapper.selectPage(new Page<>(p, s), w);
        List<DevShadow> shadows = r.getRecords();
        Map<String, String> locationNames = locationNames(shadows);

        List<CabinetMonitorRow> rows = shadows.stream()
                .map(e -> new CabinetMonitorRow(e.getCabinetNo(),
                        locationNames.get(e.getCabinetNo()),
                        e.getOnline() != null && e.getOnline() == 1,
                        e.getSnapshotAt(), e.getSignal(), e.getTemp(), e.getFaultCount()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public List<HeartbeatRecord> heartbeats(String cabinetNo, Integer limit) {
        // 直接透出 Port 的出参，不再回填成网关实体 —— 转换回实体等于把边界又拆掉。
        // limit 的钳制在实现方（那是网关的容量知识）。
        return telemetry.recentHeartbeats(cabinetNo, limit);
    }

    /** 一次 IN 查询取回本页机柜的点位名，避免逐行回表（N+1）。 */
    private Map<String, String> locationNames(List<DevShadow> shadows) {
        List<String> nos = shadows.stream().map(DevShadow::getCabinetNo).filter(java.util.Objects::nonNull).toList();
        if (nos.isEmpty()) return Map.of();
        List<DevCabinet> cabinets = cabinetMapper.selectList(
                new LambdaQueryWrapper<DevCabinet>().in(DevCabinet::getCabinetNo, nos));
        return cabinets.stream()
                .filter(c -> c.getCabinetNo() != null && c.getLocationName() != null)
                .collect(Collectors.toMap(DevCabinet::getCabinetNo, DevCabinet::getLocationName,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
