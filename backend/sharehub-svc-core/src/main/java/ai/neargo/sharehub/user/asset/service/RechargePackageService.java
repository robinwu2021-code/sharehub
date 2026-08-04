package ai.neargo.sharehub.user.asset.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargePackageRow;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkg;

import java.util.List;

/**
 * 充值套餐（usr_recharge_pkg）。<b>纯配置无业务规则 → 继承通用 CRUD</b>。
 *
 * <p>唯一的额外动作是「适用市场」：{@code markets} 在前端是 CSV，在库里是关联表
 * {@code usr_recharge_pkg_market}（[db-design §1.7]），故 {@code save} 时要同步拆写、
 * {@code toVO} 时要拼回。
 */
public interface RechargePackageService extends CrudService<UsrRechargePkg, RechargePackageRow> {

    /**
     * C 端可购套餐（C-WA-02）：只出 {@code ENABLED}，并按用户所在市场过滤。
     *
     * @param countryCode ISO alpha-2；为空则不按市场过滤（返回全部启用套餐）
     */
    List<RechargePackageRow> listForMarket(String countryCode);
}
