package ai.neargo.powerbank.dev;

import ai.neargo.powerbank.dev.entity.DevCabinet;
import ai.neargo.powerbank.dev.mapper.CabinetMapper;
import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** dev 域首次落库：dev_cabinet 空时灌内存种子。幂等。 */
@Component
@Order(3)
public class DevSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final CabinetMapper mapper;

    public DevSeeder(SeedData seed, CabinetMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Dto.Cabinet c : seed.cabinets()) {
            DevCabinet e = new DevCabinet();
            e.setCabinetNo(c.cabinetNo());
            e.setTenantId("MAIN");
            e.setSn(c.sn());
            e.setVendorCode(c.vendorCode());
            e.setModel(c.model());
            e.setLocationNo(c.locationNo());
            e.setLocationName(c.locationName());
            e.setSlotTotal(c.slotTotal());
            e.setAvailableCount(c.availableCount());
            e.setOnlineStatus(c.onlineStatus());
            e.setStatus(c.status());
            e.setFwVersion(c.fwVersion());
            e.setLastHeartbeatAt(c.lastHeartbeatAt());
            mapper.insert(e);
        }
    }
}
