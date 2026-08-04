package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.dto.Dto.Vendor;
import ai.neargo.sharehub.gw.entity.GwVendor;
import ai.neargo.sharehub.gw.mapper.GwVendorMapper;
import ai.neargo.sharehub.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** gw 域首次落库：{@code gw_vendor} 空时灌内存种子（VendorController 退役 SeedData 后表不能是白板）。幂等。 */
@Component
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(5)
public class VendorSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final GwVendorMapper mapper;

    public VendorSeeder(SeedData seed, GwVendorMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Vendor v : seed.vendors()) {
            GwVendor e = new GwVendor();
            e.setVendorCode(v.vendorCode());
            e.setName(v.name());
            e.setAccessMode(v.accessMode());
            e.setStatus(v.status());
            e.setApiBase(v.apiBase());
            e.setDeviceCount(v.deviceCount());
            mapper.insert(e);
        }
    }
}
