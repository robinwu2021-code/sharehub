package ai.neargo.powerbank.loc;

import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.loc.entity.LocContract;
import ai.neargo.powerbank.loc.entity.LocLocation;
import ai.neargo.powerbank.loc.entity.LocSite;
import ai.neargo.powerbank.loc.entity.LocVenue;
import ai.neargo.powerbank.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.LocationMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.VenueMapper;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * loc 域首次落库：DB 表为空时，把内存种子（SeedData）灌入 MariaDB。幂等（非空则跳过）。
 * 让 P4 持久化切换零手工插数，且与内存种子数据一致。
 */
@Component
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
            for (Dto.Site s : seed.sites()) {
                LocSite e = new LocSite();
                e.setSiteNo(s.siteNo());
                e.setTenantId("MAIN");
                e.setName(s.name());
                e.setVenueName(s.venueName());
                e.setAgentNo(s.agentNo());
                e.setRegionId(s.regionId());
                e.setAddress(s.address());
                e.setSceneType(s.sceneType());
                e.setPointCount(s.pointCount());
                e.setCabinetCount(s.cabinetCount());
                e.setStatus(s.status());
                siteMapper.insert(e);
            }
        }
        if (locationMapper.selectCount(null) == 0) {
            for (Dto.Location l : seed.locations()) {
                LocLocation e = new LocLocation();
                e.setLocationNo(l.locationNo());
                e.setTenantId("MAIN");
                e.setName(l.name());
                e.setSiteNo(l.siteNo());
                e.setSiteName(l.siteName());
                e.setSpotDesc(l.spotDesc());
                e.setCabinetCount(l.cabinetCount());
                e.setStatus(l.status());
                locationMapper.insert(e);
            }
        }
        if (venueMapper.selectCount(null) == 0) {
            for (Dto.Venue v : seed.venues()) {
                LocVenue e = new LocVenue();
                e.setVenueNo(v.venueNo());
                e.setTenantId("MAIN");
                e.setName(v.name());
                e.setContact(v.contact());
                e.setIndustry(v.industry());
                e.setLocationCount(v.locationCount());
                venueMapper.insert(e);
            }
        }
        if (contractMapper.selectCount(null) == 0) {
            for (Dto.Contract c : seed.contracts()) {
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
