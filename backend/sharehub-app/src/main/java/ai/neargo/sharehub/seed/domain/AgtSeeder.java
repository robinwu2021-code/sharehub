package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.sharehub.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** agt 域首次落库：agt_agent 空时灌内存种子。幂等（非空跳过）。 */
@Component
// 演示种子数据：**默认关闭**（sharehub.seed.enabled=true 才装配）。
//
// 原先无任何门禁 —— 任何空库启动都会灌入 12 个假代理商 / 20 个假站点 / 演示订单，
// **生产首次上线会被写入演示数据**，且因为 seeder 幂等（有数据即跳过），
// 一旦灌入就再也不会被覆盖或提示。这不是拆分问题，是上线事故。
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(2)
public class AgtSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final AgentMapper mapper;

    public AgtSeeder(SeedData seed, AgentMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Agent a : seed.agents()) {
            AgtAgent e = new AgtAgent();
            e.setAgentNo(a.agentNo());
            e.setTenantId("MAIN");
            e.setName(a.name());
            e.setContact(a.contact());
            /*
             * `region_scope` 是 **JSON** 列（DDL 注释：辖域(区域数组)），
             * 而种子给的是一个裸区域名 —— 直接写会触发 JSON 的 CHECK 约束
             * 「CONSTRAINT `agt_agent.region_scope` failed」，灌种失败、应用起不来。
             * 按列的本意包成单元素数组。
             */
            e.setRegionScope(a.regionScope() == null ? null
                    : "[\"" + a.regionScope().replace("\"", "\\\"") + "\"]");
            e.setShareRate(a.shareRate());
            // 机柜数不落库：聚合值，实体与库里都已没有这一列
            e.setStatus(a.status());
            mapper.insert(e);
        }
    }
}
