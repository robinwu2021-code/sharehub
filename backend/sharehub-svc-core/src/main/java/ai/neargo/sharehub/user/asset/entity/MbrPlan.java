package ai.neargo.sharehub.user.asset.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 会员方案 / 次卡（mbr_plan，[db-design §6.3]，C-MB-01）。纯配置 → 通用 CRUD。
 * 用户可见文本按 [db-design §1.5] 走三语三列（{@code name}/{@code nameEn}/{@code nameAr}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mbr_plan")
public class MbrPlan extends BaseEntity {

    private String planNo;

    private String name;

    private String nameEn;

    private String nameAr;

    /** MONTHLY(包月) / TIMES(次卡) / RIGHTS(权益卡)。 */
    private String cardType;

    private BigDecimal price;

    private String currency;

    /** 有效期天数（MONTHLY/RIGHTS）。 */
    private Integer periodDays;

    /** 总次数（TIMES 卡）。 */
    private Integer timesTotal;

    /** 权益 JSON：免费时长 / 折扣率 / 免押提额。 */
    private String rights;

    /** TINYINT(1)：是否支持自动续费。 */
    private Integer autoRenew;

    private Integer sortNo;

    /** ENABLED / DISABLED。 */
    private String status;
}
