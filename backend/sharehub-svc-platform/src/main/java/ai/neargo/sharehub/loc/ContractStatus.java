package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 合同状态（2026-09-25 定：合同走审批）。与 ops-web {@code ContractStatus} 同名同值。 */
public enum ContractStatus {
    /** 草稿：唯一可改条款的状态。 */
    DRAFT,
    /** 待审批。 */
    PENDING,
    /** 已批待生效：审批通过，等签署件与生效日。 */
    SIGNED,
    /** 生效中：场地方分成的依据；同站点任一时刻最多一份。 */
    ACTIVE,
    /** 已到期（含被续签合同替代）。 */
    EXPIRED,
    /** 已终止（提前终止）。 */
    TERMINATED;

    public static ContractStatus of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("合同状态非法: " + v));
    }
}
