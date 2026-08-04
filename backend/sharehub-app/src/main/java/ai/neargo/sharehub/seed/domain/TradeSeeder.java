package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.dto.Dto;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.seed.SeedData;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** trade 域首次落库：ord_order 空时灌内存种子（120 单）。幂等。 */
@Component
// 演示种子数据：**默认关闭**（sharehub.seed.enabled=true 才装配）。
//
// 原先无任何门禁 —— 任何空库启动都会灌入 12 个假代理商 / 20 个假站点 / 演示订单，
// **生产首次上线会被写入演示数据**，且因为 seeder 幂等（有数据即跳过），
// 一旦灌入就再也不会被覆盖或提示。这不是拆分问题，是上线事故。
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
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
        for (RentOrder o : seed.orders()) {
            OrdOrder e = new OrdOrder();
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
