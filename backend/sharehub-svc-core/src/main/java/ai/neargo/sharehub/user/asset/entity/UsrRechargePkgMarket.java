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
/**
 * <b>关联表不参与逻辑删除</b>（{@code excludeProperty = "deleted"}）。
 *
 * <p>继承 {@code BaseEntity} 会让 MyBatis-Plus 的 {@code delete()} 变成
 * {@code SET deleted = 1}，而本表的唯一键里没有 {@code deleted} ——
 * 「全量重写」的先删后插于是必然撞键：
 * {@code Duplicate entry ... for key uk_rpkg_market} → 500。
 * 症状是**第二次保存就报服务器错误**（第一次配、以及只增不减都正常，
 * 所以它躲过了所有只测「配置一次」的路径）。
 *
 * <p>物理删除在这里是对的：一行只是「某某在某某可用」的事实，
 * 没有独立的审计价值 —— 该留痕的是父记录被谁改成了什么。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "usr_recharge_pkg_market", excludeProperty = "deleted")
public class UsrRechargePkgMarket extends BaseEntity {

    private String packageNo;

    private String countryCode;
}
