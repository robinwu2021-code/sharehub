package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * {@link CabinetQueryPort} 的本地实现，住在 core 侧。
 *
 * <p><b>两个方法都走数据范围过滤</b>（MyBatis 拦截器按当前会话注入），
 * 这是本接口与 {@code DeviceOwnershipPort} 写方法的关键差异 —— 见接口注释。
 */
@Service
public class LocalCabinetQuery implements CabinetQueryPort {

    private static final int MAX_LIMIT = 500;

    private final CabinetMapper cabinets;

    public LocalCabinetQuery(CabinetMapper cabinets) {
        this.cabinets = cabinets;
    }

    @Override
    public List<CabinetBrief> assignable(String keyword, String agentNo, Integer limit) {
        int n = (limit == null || limit < 1) ? 100 : Math.min(limit, MAX_LIMIT);

        LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<>();
        // 已归档的机柜不进候选池：归档=业务上已停用，划给代理只会让对方看到一台不能用的柜子
        w.isNull(DevCabinet::getArchivedAt);
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(DevCabinet::getCabinetNo, keyword)
                    .or().like(DevCabinet::getLocationName, keyword)
                    .or().like(DevCabinet::getSn, keyword));
        }
        if (DIRECT_OPERATED.equals(agentNo)) {
            w.isNull(DevCabinet::getAgentNo);
        } else if (agentNo != null && !agentNo.isBlank()) {
            w.eq(DevCabinet::getAgentNo, agentNo);
        }
        w.orderByAsc(DevCabinet::getCabinetNo).last("limit " + n);

        return cabinets.selectList(w).stream().map(LocalCabinetQuery::toBrief).toList();
    }

    @Override
    public List<CabinetBrief> briefsByNos(Collection<String> cabinetNos) {
        if (cabinetNos == null || cabinetNos.isEmpty()) return List.of();
        return cabinets.selectList(new LambdaQueryWrapper<DevCabinet>()
                        .in(DevCabinet::getCabinetNo, cabinetNos))
                .stream().map(LocalCabinetQuery::toBrief).toList();
    }

    private static CabinetBrief toBrief(DevCabinet c) {
        // 展示名取点位名 —— 运营认的是「哪个点位的柜子」，机柜编号只是二级识别
        String name = (c.getLocationName() == null || c.getLocationName().isBlank())
                ? c.getCabinetNo() : c.getLocationName();
        return new CabinetBrief(c.getCabinetNo(), name, c.getAgentNo(), c.getSiteNo());
    }
}
