package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import java.util.Map;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.VenueMapper;
import ai.neargo.sharehub.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * loc 域首次落库：DB 表为空时，把内存种子（SeedData）灌入 MariaDB。幂等（非空则跳过）。
 * 让 P4 持久化切换零手工插数，且与内存种子数据一致。
 */
@Component
// 演示种子数据：**默认关闭**（sharehub.seed.enabled=true 才装配）。
//
// 原先无任何门禁 —— 任何空库启动都会灌入 12 个假代理商 / 20 个假站点 / 演示订单，
// **生产首次上线会被写入演示数据**，且因为 seeder 幂等（有数据即跳过），
// 一旦灌入就再也不会被覆盖或提示。这不是拆分问题，是上线事故。
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(1)
public class LocSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final SiteMapper siteMapper;
    private final LocationMapper locationMapper;
    private final VenueMapper venueMapper;
    private final ContractMapper contractMapper;

    public LocSeeder(SeedData seed, SiteMapper siteMapper, LocationMapper locationMapper,
                     VenueMapper venueMapper, ContractMapper contractMapper) {
        this.seed = seed;
        this.siteMapper = siteMapper;
        this.locationMapper = locationMapper;
        this.venueMapper = venueMapper;
        this.contractMapper = contractMapper;
    }

    @Override
    public void run(String... args) {
        if (siteMapper.selectCount(null) == 0) {
            for (Site s : seed.sites()) {
                LocSite e = new LocSite();
                e.setSiteNo(s.siteNo());
                e.setTenantId("MAIN");
                e.setName(s.name());
                e.setVenueName(s.venueName());
                e.setAgentNo(s.agentNo());
                e.setRegionId(s.regionId());
                e.setAddress(s.address());
                e.setSceneType(s.sceneType());
                e.setLng(s.lng());
                e.setLat(s.lat());
                e.setVenueNo(s.venueNo());
                // 点位数/机柜数不再落库：它们是聚合值，实体与库里都已没有对应列
                e.setStatus(s.status());
                siteMapper.insert(e);
            }
        }
        if (locationMapper.selectCount(null) == 0) {
            for (Location l : seed.locations()) {
                LocLocation e = new LocLocation();
                e.setLocationNo(l.locationNo());
                e.setTenantId("MAIN");
                e.setName(l.name());
                e.setSiteNo(l.siteNo());
                e.setSiteName(l.siteName());
                e.setSpotDesc(l.spotDesc());
                e.setStatus(l.status());
                locationMapper.insert(e);
            }
        }
        if (venueMapper.selectCount(null) == 0) {
            for (Venue v : seed.venues()) {
                LocVenue e = new LocVenue();
                e.setVenueNo(v.venueNo());
                e.setTenantId("MAIN");
                e.setName(v.name());
                e.setContact(v.contact());
                e.setIndustry(v.industry());
                // 站点数不落库：聚合值，实体与库里都已没有这一列
                venueMapper.insert(e);
            }
        }
        if (contractMapper.selectCount(null) == 0) {
            Map<String, String> venueNoByName = new java.util.HashMap<>();
            for (Venue v : seed.venues()) venueNoByName.put(v.name(), v.venueNo());
            Map<String, String> siteNoByName = new java.util.HashMap<>();
            for (Site st : seed.sites()) siteNoByName.putIfAbsent(st.name(), st.siteNo());

            for (Contract c : seed.contracts()) {
                LocContract e = new LocContract();
                e.setContractNo(c.contractNo());
                e.setTenantId("MAIN");
                /*
                 * **编号必须填**：`venue_no` / `site_no` 是 NOT NULL 且无默认值，
                 * 而种子的 Contract 只带名字 —— 干净库上这一行会直接
                 * 「Field 'venue_no' doesn't have a default value」，灌种失败、应用起不来。
                 * （2026-09-23 生产上就是这样起不来的。）
                 *
                 * 按名字反查编号在这里是可靠的：种子内部的场地方名与站点名都唯一。
                 * **只有种子能这么做** —— 业务代码一律按编号走（同名场地方会把钱分错家）。
                 */
                e.setVenueNo(venueNoByName.get(c.venueName()));
                e.setSiteNo(siteNoByName.get(c.siteName()));
                e.setVenueName(c.venueName());
                e.setSiteName(c.siteName());
                e.setCurrency("AED");
                e.setShareRate(c.shareRate());
                e.setEntryFee(c.entryFee());
                /*
                 * `start_at` / `end_at` 是 **DATE** 列，而种子给的是完整 UTC ISO
                 * （`2026-09-23T03:00:00Z`）—— 直接写会
                 * 「Incorrect date value … Data truncation」。
                 * 干净库上这一步一直是失败的，只是从没有人在干净库上灌过种。
                 */
                e.setStartAt(SeedTime.date(c.startAt()));
                e.setEndAt(SeedTime.date(c.endAt()));
                e.setStatus(c.status());
                contractMapper.insert(e);
            }
        }
    }

    /** ISO 时刻 → DATE 列能吃的 {@code YYYY-MM-DD}。传 null 原样返回。 */
    private static String dateOf(String iso) {
        return iso == null ? null : (iso.length() >= 10 ? iso.substring(0, 10) : iso);
    }
}
