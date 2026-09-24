package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.ext.service.VenueCreator;
import org.springframework.stereotype.Component;

/**
 * {@link VenueCreator} 的真实实现 —— 取代 {@code StubVenueCreator}。
 *
 * <p><b>补的是一个「成功了但什么都没发生」的缺陷</b>：占位实现只取号、不落 {@code loc_venue} 行，
 * 于是进件审核通过、页面提示成功、{@code venue_no} 也回填了，
 * 而**场地方列表里根本没有这一条** —— 运营下一步想给它签合同时才发现查无此人。
 * 这类错比报错难查得多：每一步都显示正常。
 *
 * <p>按 {@link VenueCreator} 类注释里写好的接缝落地：调 {@link LocService#saveVenue}，
 * 审核流程一行未改。
 */
@Component
public class RealVenueCreator implements VenueCreator {

    private final LocService loc;

    public RealVenueCreator(LocService loc) {
        this.loc = loc;
    }

    @Override
    public String create(String venueName, String contact, String industry) {
        LocVenue v = new LocVenue();
        v.setName(venueName);
        v.setContact(contact);
        v.setIndustry(industry);
        // venueNo 留空 → saveVenue 取号（BizKey.VENUE）。
        // 取号不在这里做：号段规则只该有一处，两处各取各的迟早会分叉。
        return loc.saveVenue(v).venueNo();
    }
}
