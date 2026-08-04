package ai.neargo.sharehub.user.asset.mapper;

import ai.neargo.sharehub.user.asset.entity.MbrPlan;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;
import ai.neargo.sharehub.user.asset.entity.UsrRechargeOrder;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkg;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkgMarket;
import ai.neargo.sharehub.user.asset.entity.UsrWallet;
import ai.neargo.sharehub.user.asset.entity.UsrWalletTxn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** user/asset 子域 Mapper（钱包 / 充值 / 会员）。嵌套接口，随 markerInterface 扫描。 */
public final class UserAssetMappers {

    private UserAssetMappers() {
    }

    public interface UsrWalletMapper extends BaseMapper<UsrWallet> {
    }

    public interface UsrWalletTxnMapper extends BaseMapper<UsrWalletTxn> {
    }

    public interface UsrRechargePkgMapper extends BaseMapper<UsrRechargePkg> {
    }

    public interface UsrRechargePkgMarketMapper extends BaseMapper<UsrRechargePkgMarket> {
    }

    public interface UsrRechargeOrderMapper extends BaseMapper<UsrRechargeOrder> {
    }

    public interface MbrPlanMapper extends BaseMapper<MbrPlan> {
    }

    public interface UsrMembershipMapper extends BaseMapper<UsrMembership> {
    }
}
