package ai.neargo.sharehub.user.asset.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 充值套餐适用市场（usr_recharge_pkg_market，[db-design §1.7] 多值拆表）。
 * 一行 = 一个套餐在一个国家可售；UK(package_no, country_code)。
 * {@code countryCode} 为 ISO alpha-2，指向 {@code md_market_country.country_code}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_recharge_pkg_market")
public class UsrRechargePkgMarket extends BaseEntity {

    private String packageNo;

    private String countryCode;
}
