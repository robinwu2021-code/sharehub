package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务规则（sys_biz_rule）—— **三分区单例**：每租户每 {@code category} 恰好一行，
 * UK {@code (tenant_id, category)}。
 *
 * <p>{@code category} ∈ {@code WITHDRAW}(提现) / {@code RESERVATION}(预约) / {@code BILLING}(计费默认)。
 * 运营端是一页三分区、三个独立保存按钮 → 保存语义是 {@code Partial}：
 * <b>只合并传入的分区，绝不覆盖其它两个</b>（见 {@code BizRuleServiceImpl#save}）。
 *
 * <p><b>{@code WITHDRAW} 分区是提现手续费口径的唯一来源</b>：
 * {@code fin_withdrawal.fee} 必须取自这里的 {@code feeRate}/{@code feeCap}，
 * 财务侧不得另存一份阈值 —— 两处口径一旦分叉，对账永远差钱。
 *
 * <p>{@code rule} 是 JSON 文本（本骨架用 String 承载，序列化在 service 层做，
 * 不引入 typeHandler 以免与其它域的 JSON 列处理方式分叉）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_biz_rule")
public class SysBizRule extends BaseEntity {
    /** WITHDRAW/RESERVATION/BILLING。 */
    private String category;
    /** 规则体 JSON 文本。 */
    private String rule;
    /** 规则内金额阈值的币种。 */
    private String currency;
    /** 最后修改人 employee_no。 */
    private String updatedBy;
}
