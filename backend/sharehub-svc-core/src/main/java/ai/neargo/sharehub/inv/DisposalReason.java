package ai.neargo.sharehub.inv;

/** 报废原因码（{@code inv_disposal.reason_code}，V203）。枚举不是自由文本 —— 否则汇总不出「这个月坏了多少」。 */
public enum DisposalReason {

    /** 损坏。 */
    BROKEN,
    /** 淘汰（型号停用、老化）。 */
    OBSOLETE,
    /** 其他（说明里写清）。 */
    OTHER
}
