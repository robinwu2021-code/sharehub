package ai.neargo.powerbank.trade;

import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.seed.SeedData;
import ai.neargo.powerbank.trade.entity.OrdRent;
import ai.neargo.powerbank.trade.mapper.OrdMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** trade 域首次落库：ord_rent 空时灌内存种子（120 单）。幂等。 */
@Component
@Order(5)
public class TradeSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final OrdMapper mapper;

    public TradeSeeder(SeedData seed, OrdMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Dto.RentOrder o : seed.orders()) {
            OrdRent e = new OrdRent();
            e.setOrderNo(o.orderNo());
            e.setTenantId("MAIN");
            e.setCUserNo(o.cUserNo());
            e.setCabinetNo(o.cabinetNo());
            e.setReturnCabinetNo(o.returnCabinetNo());
            e.setPowerbankNo(o.powerbankNo());
            e.setLocationName(o.locationName());
            e.setStatus(o.status());
            e.setRentStartAt(o.rentStartAt());
            e.setRentEndAt(o.rentEndAt());
            e.setDurationMin(o.durationMin());
            e.setFeeAmount(o.feeAmount());
            e.setDepositAmount(o.depositAmount());
            e.setCurrency(o.currency());
            mapper.insert(e);
        }
    }
}
