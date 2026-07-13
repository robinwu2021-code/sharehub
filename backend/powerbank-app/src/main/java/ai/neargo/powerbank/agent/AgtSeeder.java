package ai.neargo.powerbank.agent;

import ai.neargo.powerbank.agent.entity.AgtAgent;
import ai.neargo.powerbank.agent.mapper.AgentMapper;
import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** agt 域首次落库：agt_agent 空时灌内存种子。幂等（非空跳过）。 */
@Component
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
        for (Dto.Agent a : seed.agents()) {
            AgtAgent e = new AgtAgent();
            e.setAgentNo(a.agentNo());
            e.setTenantId("MAIN");
            e.setName(a.name());
            e.setContact(a.contact());
            e.setRegionScope(a.regionScope());
            e.setShareRate(a.shareRate());
            e.setCabinetCount(a.cabinetCount());
            e.setStatus(a.status());
            mapper.insert(e);
        }
    }
}
