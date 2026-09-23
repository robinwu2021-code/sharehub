package ai.neargo.sharehub.loc.ext;

import java.util.Arrays;
import java.util.Optional;

/**
 * 商机归属方类型（ADR-027 §五 / V55）。
 *
 * <p>决定 {@code loc_lead.owner} 里那个号属于哪个命名空间 —— employee_no 还是 agent_no。
 * 判错的后果是拓展佣金算给不存在的人，或者白付一笔给自己的员工，两种都不报错。
 */
public enum LeadOwnerType {

    /** 自己人谈下来的。不产生对外的拓展佣金。 */
    STAFF,
    /** 伙伴谈下来的。签下并落成站点后写一行 {@code loc_site_agent(role=DEVELOP)}，作为佣金依据。 */
    AGENT;

    public static Optional<LeadOwnerType> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }
}
