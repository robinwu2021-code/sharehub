package ai.neargo.sharehub.loc.ext;

import java.util.Arrays;
import java.util.Optional;

/**
 * 伙伴在某个站点承担的责任（ADR-027 §二）。
 *
 * <h3>为什么是 4 档而不是 ADR 原议的 5 档</h3>
 * 2026-09-23 定：效果管理（MANAGE）先并进 {@link #OPERATE}。首批伙伴多半两件都做，
 * 分开只会让每站多配一行、每单多一条分润记录，而受益方与比例完全一样。
 * <b>要分的时候再加一档不迁移任何数据；反向（把合并过的拆回去）才要迁。</b>
 *
 * <h3>REFER 与 DEVELOP 互斥</h3>
 * 牵线是拓展的弱形式 —— 同一个人在同一个站点上只能算其一，否则同一件事付两份钱。
 * 该约束在服务层强制（库里两者是不同的 role 值，UK 拦不住）。
 */
public enum SiteAgentRole {

    /** 出资：买设备。对价是资产收益，按 GMV 比例持续。 */
    INVEST,
    /** 拓展：找场地、商务谈判、签进场合同。对价是拓展佣金。 */
    DEVELOP,
    /** 运维：装机、补货、维修、接工单 —— **并含经营优化**（原 MANAGE）。 */
    OPERATE,
    /** 牵线：只介绍关系，不谈判。对价是一次性介绍费（签约时付，不进逐单分润）。 */
    REFER;

    public static Optional<SiteAgentRole> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    /** 牵线与拓展互斥：同一人在同一站点只能算其一。 */
    public boolean conflictsWith(SiteAgentRole other) {
        return (this == REFER && other == DEVELOP) || (this == DEVELOP && other == REFER);
    }
}
