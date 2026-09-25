package ai.neargo.sharehub.agent.ext;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.api.platform.event.AssetAssignedEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代理划拨的归属回写（ADR-012）：把 {@code agt_assignment} 的划拨结果同步到
 * {@code loc_site} / {@code loc_location} / {@code dev_cabinet} 的 {@code agent_no} 冗余列。
 *
 * <p><b>为什么必须回写</b>：数据范围（{@code DataScopeHandler}）过滤的是这几张表上的 {@code agent_no} 列。
 * 只落划拨流水不回写 = 划拨在业务上生效了、但代理登录后<b>看不到</b>划给他的资产 ——
 * 流水与现状两张皮。
 *
 * <h3>级联规则：自上而下，最具体的划拨优先</h3>
 * <pre>
 *   SITE     划拨 → loc_site + 其下全部 loc_location + 全部 dev_cabinet
 *   LOCATION 划拨 → loc_location + 其下全部 dev_cabinet   （站点不动）
 *   CABINET  划拨 → dev_cabinet 单台                      （点位/站点不动）
 * </pre>
 *
 * <p>db-design §3.4 原话是「站点归属为权威、cabinet.agent_no 冗余」，但运营端菜单同时提供
 * 「机柜/点位划拨」三种粒度。二者并存的自洽解释是<b>「最具体的一次划拨说了算」</b>：
 * 允许把直营站点里的某个点位单独给代理（真实场景：一个商场自营，其中一个门店交给代理运营），
 * 也允许单台机柜归属特殊化。因此权威源不是「站点」而是「该层级最近一次划拨」。
 *
 * <h3>⚠️ 明确不回写 {@code ord_order} / {@code wo_order}</h3>
 * 这两张表的 {@code agent_no} 是<b>下单/开单时刻的快照</b>，不是当前归属：
 * <ul>
 *   <li>订单收入归属的是<b>当时</b>持有该站点的代理，分润已按当时的归属结算完毕；</li>
 *   <li>若随划拨回溯改写，老代理会失去自己历史订单的可见性，新代理则看到不属于他的收入 ——
 *       且与已生成的 {@code share_record} 对不上。</li>
 * </ul>
 * 所以站点转手后：历史单仍归老代理可见，新单自动归新代理。这是有意为之，不是漏改。
 *
 * <p>（附带说明：{@code ddl/pb_core-v2-datascope.sql} 的一次性回填是按<b>当前</b>机柜归属反推历史单的，
 * 对「已转手过的站点」其历史单归属会偏向新代理。这是存量数据的近似，无法从现有数据还原真实的历史归属。）
 */
@Component
public class AgentOwnershipSync {

    private final LocMappers.SiteMapper siteMapper;
    private final LocMappers.LocationMapper locationMapper;
    private final DeviceOwnershipPort deviceOwnership;
    private final DomainEventBus eventBus;

    public AgentOwnershipSync(LocMappers.SiteMapper siteMapper,
                              LocMappers.LocationMapper locationMapper,
                              DeviceOwnershipPort deviceOwnership,
                              DomainEventBus eventBus) {
        this.siteMapper = siteMapper;
        this.locationMapper = locationMapper;
        this.deviceOwnership = deviceOwnership;
        this.eventBus = eventBus;
    }

    /**
     * 按划拨对象回写归属。
     *
     * @param targetType CABINET / LOCATION / SITE
     * @param targetNo   对象业务键
     * @param agentNo    归属代理；{@code null} = 收回（转为平台直营）
     * @return 受影响的行数（用于流水留痕与人工核对）
     */
    public int apply(String targetType, String targetNo, String agentNo) {
        // 存在性检查**带数据范围**——它同时是授权闸门：看不到的对象就不该划拨（返回「不存在」而非越权改动）。
        requireVisible(targetType, targetNo);

        // 级联更新**豁免数据范围**——级联必须完整。若更新也被 scope 过滤，
        // 会出现「站点已改归属、但其中一部分点位/机柜没改」的部分级联，
        // 数据范围随后就按这份不一致的数据过滤，比不划拨更糟。
        return DataScopeContext.executeWithoutScope(() -> switch (targetType) {
            case "SITE" -> applySite(targetNo, agentNo);
            case "LOCATION" -> applyLocation(targetNo, agentNo);
            case "CABINET" -> applyCabinet(targetNo, agentNo);
            default -> throw new IllegalArgumentException("划拨对象类型非法: " + targetType);
        });
    }

    /**
     * 发布归属变更事件，由 core 订阅后回写机柜（ADR-019 决策二）。
     *
     * <p><b>为什么机柜不在本事务里改</b>：`dev_cabinet` 归 core 所有，
     * platform 的事务不该跨到 core 的表 —— 拆分后那就是分布式事务。
     * 划拨是低频运营动作、不在借还主链路，**秒级最终一致是可接受的代价**；
     * 要强一致就只能让「代理商」与「设备资产」不可分开部署，代价大得多。
     *
     * <p>事件自带 {@code locationNos}，消费方不必回查 platform 的点位表。
     */
    private void publishAssigned(String targetType, String targetNo, String agentNo,
                                 String siteNo, List<String> locationNos, String cabinetNo) {
        eventBus.publish(new AssetAssignedEvent(targetType, targetNo, agentNo, siteNo,
                locationNos == null ? List.of() : locationNos, cabinetNo));
    }

    /** 带数据范围的存在性检查：既校验对象存在，也校验当前操作者有权触达它。 */
    private void requireVisible(String targetType, String targetNo) {
        boolean ok = switch (targetType) {
            case "SITE" -> siteMapper.exists(
                    new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, targetNo));
            case "LOCATION" -> locationMapper.exists(
                    new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getLocationNo, targetNo));
            case "CABINET" -> deviceOwnership.existsInScope(targetNo);
            default -> throw new IllegalArgumentException("划拨对象类型非法: " + targetType);
        };
        if (!ok) throw BizException.notFound(targetType + " " + targetNo);
    }

    private int applySite(String siteNo, String agentNo) {
        int n = siteMapper.update(null, new LambdaUpdateWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo)
                .set(LocSite::getAgentNo, agentNo));

        // 级联：站点下全部点位
        n += locationMapper.update(null, new LambdaUpdateWrapper<LocLocation>()
                .eq(LocLocation::getSiteNo, siteNo)
                .set(LocLocation::getAgentNo, agentNo));

        // 机柜（core 侧）改为事件驱动最终一致，不在本事务内写
        publishAssigned("SITE", siteNo, agentNo, siteNo, locationNosOf(siteNo), null);
        return n;
    }

    private int applyLocation(String locationNo, String agentNo) {
        LocLocation loc = locationMapper.selectOne(
                new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getLocationNo, locationNo).last("limit 1"));

        int n = locationMapper.update(null, new LambdaUpdateWrapper<LocLocation>()
                .eq(LocLocation::getLocationNo, locationNo)
                .set(LocLocation::getAgentNo, agentNo));

        publishAssigned("LOCATION", locationNo, agentNo,
                loc == null ? null : loc.getSiteNo(), List.of(locationNo), null);
        return n;
    }

    private int applyCabinet(String cabinetNo, String agentNo) {
        publishAssigned("CABINET", cabinetNo, agentNo, null, List.of(), cabinetNo);
        return 0;   // core 侧异步回写，此处无法预知行数
    }

    private List<String> locationNosOf(String siteNo) {
        return locationMapper.selectList(new LambdaQueryWrapper<LocLocation>()
                        .select(LocLocation::getLocationNo)
                        .eq(LocLocation::getSiteNo, siteNo))
                .stream().map(LocLocation::getLocationNo).filter(java.util.Objects::nonNull).toList();
    }
}
