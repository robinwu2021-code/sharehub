package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry;
import ai.neargo.sharehub.trade.pay.service.PaymentService;
import ai.neargo.sharehub.user.asset.RechargeOrderStatus;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeResultVO;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.WalletOverview;
import ai.neargo.sharehub.user.asset.entity.UsrRechargeOrder;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkg;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargeOrderMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargePkgMapper;
import ai.neargo.sharehub.user.asset.service.RechargeService;
import ai.neargo.sharehub.user.asset.service.WalletService;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 充值实现。金额取自套餐，入账走 {@link WalletService#credit}，通道走 {@link PaymentService}（桩）。 */
@Service
public class RechargeServiceImpl implements RechargeService {

    private static final Logger log = LoggerFactory.getLogger(RechargeServiceImpl.class);

    /** 流水的业务类型：对账时要能把充值入账单独筛出来。 */
    private static final String BIZ_RECHARGE = "RECHARGE";
    private static final String PAY_TYPE_RECHARGE = "RECHARGE";
    private static final String TENANT_MAIN = "MAIN";
    private static final String PKG_ENABLED = "ENABLED";

    private final UsrRechargePkgMapper packages;
    private final UsrRechargeOrderMapper orders;
    private final WalletService wallets;
    private final PaymentService payments;
    private final NicknameLookup nicknames;

    public RechargeServiceImpl(UsrRechargePkgMapper packages, UsrRechargeOrderMapper orders,
                               WalletService wallets, PaymentService payments, NicknameLookup nicknames) {
        this.packages = packages;
        this.orders = orders;
        this.wallets = wallets;
        this.payments = payments;
        this.nicknames = nicknames;
    }

    @Override
    @Transactional
    public RechargeResultVO recharge(String cUserNo, String packageNo) {
        if (packageNo == null || packageNo.isBlank()) throw new IllegalArgumentException("请选择充值套餐");
        UsrRechargePkg pkg = packages.selectOne(new LambdaQueryWrapper<UsrRechargePkg>()
                .eq(UsrRechargePkg::getPackageNo, packageNo).last("limit 1"));
        if (pkg == null) throw BizException.notFound(packageNo);
        if (!PKG_ENABLED.equals(pkg.getStatus())) throw new IllegalArgumentException("充值套餐已停用: " + packageNo);

        BigDecimal pay = nz(pkg.getPayAmount());
        BigDecimal gift = nz(pkg.getGiftAmount());
        if (pay.signum() <= 0) throw new IllegalArgumentException("充值套餐金额异常: " + packageNo);
        String currency = pkg.getCurrency() == null || pkg.getCurrency().isBlank() ? "AED" : pkg.getCurrency();

        UsrRechargeOrder o = new UsrRechargeOrder();
        o.setRechargeNo(IdGenerator.next(BizKey.RECHARGE_ORDER));
        o.setTenantId(TENANT_MAIN);
        o.setCUserNo(cUserNo);
        o.setNickname(nicknames.byUserNo(cUserNo)); // 昵称快照：日后改名不回溯这一单
        o.setPackageNo(packageNo);
        o.setPayAmount(pay);
        o.setGiftAmount(gift);
        o.setCreditAmount(pay.add(gift));
        o.setCurrency(currency);
        o.setStatus(RechargeOrderStatus.PENDING.name());
        orders.insert(o);

        // 幂等键绑在充值单号上：同一单重复请款通道必须返回同一结果。
        // 注意这挡不住「用户连点两次」——那会产生两张单、两个键，是两笔真实充值。
        // 要挡得住需要端上传幂等键，属于接入真实收银台时一并做的事（见 TODO）。
        PayOrderEntry p = payments.pay(o.getRechargeNo(), cUserNo, PAY_TYPE_RECHARGE,
                pay, currency, null, "RCH-" + o.getRechargeNo());

        if (!"PAID".equals(p.status())) {
            // 单据留在 PENDING，钱包不动。不记日志的话，用户说「钱没到」时无从查起
            log.warn("充值未即时成功，单据留在 PENDING rechargeNo={} payNo={} status={}",
                    o.getRechargeNo(), p.payNo(), p.status());
            return new RechargeResultVO(o.getRechargeNo(), packageNo, pay, gift, o.getCreditAmount(),
                    currency, o.getStatus(), null, null);
        }

        o.setStatus(RechargeOrderStatus.PAID.name());
        o.setPspTxnNo(p.nearpayTxnNo());
        o.setPaidAt(p.paidAt() == null ? java.time.LocalDateTime.now().toString() : p.paidAt());
        orders.updateById(o);

        WalletOverview after = wallets.credit(cUserNo, pay, gift, currency,
                BIZ_RECHARGE, o.getRechargeNo(), "钱包充值 " + pkg.getName());
        log.info("充值到账 rechargeNo={} cUserNo={} pay={} gift={}", o.getRechargeNo(), cUserNo, pay, gift);

        return new RechargeResultVO(o.getRechargeNo(), packageNo, pay, gift, o.getCreditAmount(),
                currency, o.getStatus(), after.balance(), after.giftBalance());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
