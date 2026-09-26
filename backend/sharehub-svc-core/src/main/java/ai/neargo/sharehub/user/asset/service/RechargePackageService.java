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

    /**
     * 保存套餐**并同步适用市场**（{@code usr_recharge_pkg_market} 全量重写）。
     *
     * <p>单独开一个方法而不是塞进 {@code save}：基类的 {@code save} 只认实体，
     * 而市场是关联表、不在实体上。写入面此前直接调 {@code save(body.toEntity())}，
     * 于是「适用市场」这一格永远存不进去 —— 而它是必填项，
     * 且没有市场的套餐在 C 端对**每个用户**都不出现。
     *
     * @param marketsCsv ISO alpha-2 逗号分隔；null = 不动关联表，空串 = 清空
     */
    RechargePackageRow saveWithMarkets(ai.neargo.sharehub.user.asset.entity.UsrRechargePkg e, String marketsCsv);
}
