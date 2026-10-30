package ai.neargo.sharehub.loc;

import ai.neargo.sharehub.common.crud.BizNoSequence;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 站点的默认点位（V162；用户 2026-10-28：绑定不指定点位时，落到商家默认站点的默认点位）。
 *
 * <p>建站的三个入口（直营建站、为商家建站、商家默认站点）都调这里，**与建站同一事务**：
 * 站点建出来就有默认点位，不存在「站点有了、点位还没建」的中间态。
 * 「每站至多一个默认点位」由库里的唯一键 uk_loc_default 兜底。
 */
@Component
public class DefaultPoints {

    private final LocationMapper locations;
    private final JdbcTemplate jdbc;

    public DefaultPoints(LocationMapper locations, JdbcTemplate jdbc) {
        this.locations = locations;
        this.jdbc = jdbc;
    }

    /** 建默认点位，返回点位号。商家随站点（站点还没划给商家时为空，划拨时级联写入）。 */
    public String create(String siteNo, String siteName, String agentNo) {
        LocLocation e = new LocLocation();
        e.setLocationNo(nextNo(jdbc));
        e.setTenantId("MAIN");
        e.setSiteNo(siteNo);
        e.setSiteName(siteName);
        e.setAgentNo(agentNo);
        e.setName("默认点位");
        e.setStatus(LocationStatus.ACTIVE.name());
        e.setIsDefault(1);
        locations.insert(e);
        return e.getLocationNo();
    }

    /** 点位号：服务端取号（「LOC + 数字」）。此前是 LOC + (200 + 行数)，并发会撞，删过行也会撞。 */
    public static String nextNo(JdbcTemplate jdbc) {
        return BizNoSequence.next(jdbc, "loc_location", "location_no", "LOC", 3);
    }
}
