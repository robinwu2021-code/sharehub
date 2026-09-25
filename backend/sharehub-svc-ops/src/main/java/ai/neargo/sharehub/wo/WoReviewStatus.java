package ai.neargo.sharehub.wo;

/** 完工复核结果（{@code wo_order.review_status}，仅告警来源的工单）。 */
public enum WoReviewStatus {
    /** 关联告警已恢复 → 自动验收。 */
    PASSED,
    /** 关联告警仍成立 → 保持 DONE 等人工返工或放行。 */
    FAILED
}
