package ai.neargo.sharehub.loc.ext;

import java.util.Arrays;
import java.util.Optional;

/**
 * 商机阶段（列 {@code loc_lead.stage}；与 ops-web {@code LEAD_STAGES} 同值）。
 *
 * <p>叫 Status 而不叫 Stage：状态机卡口按 {@code XxxStatus} 认词表，阶段就是这条商机的状态。
 */
public enum LeadStatus {
    NEW, CONTACTED, NEGOTIATING, SIGNED, LOST;

    public static Optional<LeadStatus> of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst();
    }

    /** 还在跟的阶段：提醒、回收只管它们。 */
    public boolean open() {
        return this == NEW || this == CONTACTED || this == NEGOTIATING;
    }
}
