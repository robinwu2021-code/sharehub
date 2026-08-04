package ai.neargo.sharehub.user.asset.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 充值套餐（usr_recharge_pkg，[db-design §6.2]，C-WA-02）。纯配置，无业务规则 → 走通用 CRUD。
 *
 * <p><b>{@code markets} 不是列</b>：前端 {@code RechargePackage.markets} 是 CSV 字符串，
 * 后端按 [db-design §1.7] 拆到关联表 {@link UsrRechargePkgMarket}（UK: package_no + country_code），
 * 由 service 在 {@code toVO} 时拼回 CSV。CSV 存列无法按市场检索、也无法建外键约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_recharge_pkg")
public class UsrRechargePkg extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    /** 业务键，前缀 {@code RP}。 */
    private String packageNo;

    private String regionId;

    private String name;

    /** 实付金额。 */
    private BigDecimal payAmount;

    /** 赠送金额。 */
    private BigDecimal giftAmount;

    private String currency;

    /** 到账余额有效期（天），空 = 永久。 */
    private Integer validDays;

    private Integer sortNo;

    /** ENABLED / DISABLED。 */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
