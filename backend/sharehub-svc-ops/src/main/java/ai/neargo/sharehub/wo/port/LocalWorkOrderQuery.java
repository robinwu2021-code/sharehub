package ai.neargo.sharehub.wo.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.ops.port.WorkOrderQueryPort;
import ai.neargo.sharehub.wo.WorkOrderStatus;
import ai.neargo.sharehub.wo.entity.WoOrder;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link WorkOrderQueryPort} 的本地实现（系统读，豁免数据范围）。 */
@Service
public class LocalWorkOrderQuery implements WorkOrderQueryPort {

    /** 未完结：待验收（DONE）也算 —— 站点关了，返工单就没处落了。 */
    static final List<String> OPEN = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name(),
            WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name(), WorkOrderStatus.DONE.name());

    private final WoMapper orders;

    public LocalWorkOrderQuery(WoMapper orders) {
        this.orders = orders;
    }

    @Override
    public Map<String, Long> openCountBySites(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        Map<String, Long> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orders.selectMaps(new QueryWrapper<WoOrder>()
                        .select("site_no AS siteNo", "COUNT(*) AS cnt").in("site_no", siteNos).in("status", OPEN)
                        .groupBy("site_no")))
                .forEach(m -> out.put(String.valueOf(m.get("siteNo")), ((Number) m.get("cnt")).longValue()));
        return out;
    }

    @Override
    public java.util.Set<String> installDoneCabinets(Collection<String> cabinetNos) {
        if (cabinetNos == null || cabinetNos.isEmpty()) return java.util.Set.of();
        return DataScopeContext.executeWithoutScope(() -> orders.selectList(new QueryWrapper<WoOrder>().select("cabinet_no")
                        .in("cabinet_no", cabinetNos).eq("type", ai.neargo.sharehub.wo.ext.WorkOrderType.INSTALL.name())
                        .in("status", WorkOrderStatus.DONE.name(), WorkOrderStatus.AUDITED.name(), WorkOrderStatus.CLOSED.name())))
                .stream().map(WoOrder::getCabinetNo).collect(java.util.stream.Collectors.toSet());
    }
}
