package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 税率与发票设置（sys_tax_setting）—— 按国家配置税种/税率/税号/抬头。
 *
 * <p><b>非全局表</b>：UK 是 {@code (tenant_id, country)} 复合键，按键查询必须带 tenantId。
 * {@code country = '*'} 是默认行。
 *
 * <p>{@code ratePercent} 是**百分数口径 0..100**（如 VAT 5 存 {@code 5.00}），不是 0..1。
 * {@code includedInPrice} 决定 C 端计费展示：1=价内税（标价含税），0=价外税（结算时另加）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tax_setting")
public class SysTaxSetting extends BaseEntity {
    /** ISO alpha-2；{@code *} = 默认行。 */
    private String country;
    private String countryName;
    /** 税种名，如 VAT。 */
    private String taxName;
    /** 税率 0..100。 */
    private BigDecimal ratePercent;
    /** 税号 Tax Registration Number。 */
    private String trn;
    private String invoiceTitle;
    /** 1=含税价，0=价外税。 */
    private Integer includedInPrice;
    /** 生效日（仅日期语义）。 */
    private String effectiveFrom;
}
