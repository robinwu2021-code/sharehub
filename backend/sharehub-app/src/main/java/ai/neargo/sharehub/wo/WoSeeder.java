package ai.neargo.powerbank.wo;

import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.seed.SeedData;
import ai.neargo.powerbank.wo.entity.WoOrder;
import ai.neargo.powerbank.wo.mapper.WoMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** wo 域首次落库：wo_order 空时灌内存种子。幂等。 */
@Component
@Order(4)
public class WoSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final WoMapper mapper;

    public WoSeeder(SeedData seed, WoMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Dto.WorkOrder w : seed.workOrders()) {
            WoOrder e = new WoOrder();
            e.setWoNo(w.woNo());
            e.setTenantId("MAIN");
            e.setType(w.type());
            e.setSource(w.source());
            e.setPriority(w.priority());
            e.setCabinetNo(w.cabinetNo());
            e.setLocationName(w.locationName());
            e.setStatus(w.status());
            e.setAssigneeName(w.assigneeName());
            e.setSlaDueAt(w.slaDueAt());
            e.setDescription(w.description());
            e.setWoCreatedAt(w.createdAt());
            mapper.insert(e);
        }
    }
}
