package ai.neargo.sharehub.inv;

/** 到仓待检单状态（{@code inv_intake.status}，V192）：明细全部入库 / 处置后自动完成。 */
public enum IntakeStatus {

    /** 待处理：还有明细未入库、未处置。 */
    OPEN,
    /** 已完成：明细全部处理完。 */
    DONE
}
