package ai.neargo.sharehub.agent.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * agent 域的出入参 DTO。
 *
 * <p>原先住在顶层 {@code dto.Dto} 那个骨架期共享集合里 —— 那让 agent 域**必须依赖 app**，
 * 是拆 Maven 模块的硬阻塞。DTO 属于它描述的那个域，搬回来是归位不是重构。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * @param agentType 登记类型 AGENT / CITY_PARTNER（ADR-027）；存量与缺省为 AGENT
     */
    public record Agent(String agentNo, String name, String contact, String regionScope,
                       String agentType, double shareRate, int cabinetCount, String status) {
    }
}
