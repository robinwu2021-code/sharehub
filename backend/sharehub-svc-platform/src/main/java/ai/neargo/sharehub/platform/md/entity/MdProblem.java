package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * C端报障问题字典（md_problem）—— **C 端报障分流的字典来源**。
 *
 * <p>业务键前缀 {@code ISS}（{@link ai.neargo.sharehub.common.BizKey#PROBLEM}），
 * <b>勿与充电宝 {@code PB} 混用</b>。
 *
 * <p>{@code suggestedAction} 决定 C 端点完这条问题后的出口：
 * {@code SELF_SERVICE}（只展示答复）/ {@code TO_WORKORDER}（转工单）/
 * {@code TO_REFUND}（转退款申请）/ {@code TO_CS}（转人工客服）。
 * 同一条内容三语（{@code title/answer} 各三列，[db-design §1.5]），C 端按 {@code Accept-Language} 取。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("md_problem")
public class MdProblem extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {
    private String problemNo;
    /** RENT/RETURN/BILLING/DEVICE/ACCOUNT/OTHER。 */
    private String category;
    private String title;
    private String titleEn;
    private String titleAr;
    private String answer;
    private String answerEn;
    private String answerAr;
    /** SELF_SERVICE/TO_WORKORDER/TO_REFUND/TO_CS。 */
    private String suggestedAction;
    private Integer sortNo;
    /** ENABLED/DISABLED。 */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
