package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.loc.dto.LocDtos.Site;
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
            for (Contract c : seed.contracts()) {
                LocContract e = new LocContract();
                e.setContractNo(c.contractNo());
                e.setTenantId("MAIN");
                e.setVenueName(c.venueName());
                e.setSiteName(c.siteName());
                e.setShareRate(c.shareRate());
                e.setEntryFee(c.entryFee());
                e.setStartAt(c.startAt());
                e.setEndAt(c.endAt());
                e.setStatus(c.status());
                contractMapper.insert(e);
            }
        }
    }
}
