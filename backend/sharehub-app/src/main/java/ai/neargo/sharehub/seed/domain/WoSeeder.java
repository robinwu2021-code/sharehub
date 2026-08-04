package ai.neargo.sharehub.seed.domain;

// Seeder **留在 app 而不是随业务包进 svc**：它是「演示数据装配」不是业务实现 ——
// 依赖 SeedData（全局单例）与各域实体，本质是跨域的启动期装配，属于可部署单元的职责
// （ADR-017：app-* 持有启动类+配置+装配）。
//
// 这也顺带解开了 seed 的拆分阻塞：svc 里不再有任何 seeder，SeedData 不必被拆散。

import ai.neargo.sharehub.dto.Dto;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.seed.SeedData;
import ai.neargo.sharehub.wo.entity.WoOrder;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** wo 域首次落库：wo_order 空时灌内存种子。幂等。 */
@Component
// 演示种子数据：**默认关闭**（sharehub.seed.enabled=true 才装配）。
//
// 原先无任何门禁 —— 任何空库启动都会灌入 12 个假代理商 / 20 个假站点 / 演示订单，
// **生产首次上线会被写入演示数据**，且因为 seeder 幂等（有数据即跳过），
// 一旦灌入就再也不会被覆盖或提示。这不是拆分问题，是上线事故。
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
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
        for (WorkOrder w : seed.workOrders()) {
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
