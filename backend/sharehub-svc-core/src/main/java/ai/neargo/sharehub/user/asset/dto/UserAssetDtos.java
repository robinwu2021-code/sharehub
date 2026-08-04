package ai.neargo.sharehub.user.asset.dto;

import java.math.BigDecimal;

/**
 * user/asset 子域出参 VO（钱包 / 充值 / 会员）。
 * 字段镜像 ops-web {@code lib/types/user.ts}（{@code Wallet}/{@code RechargePackage}/{@code Member}）
 * 与 {@code lib/types/finance.ts}（{@code RechargeOrder}）。
 */
public final class UserAssetDtos {

    private UserAssetDtos() {
    }

    /**
     * 运营端钱包行，镜像前端 {@code Wallet}。
     *
     * <p><b>后四个字段是聚合值不是列</b>（[db-design §1.4]）：
     * {@code orderCount}/{@code orderAmount} 来自 {@code ord_order}、
     * {@code rechargeCount}/{@code rechargeAmount} 来自 {@code usr_recharge_order}，
     * 均按 {@code c_user_no} 现算。落到 {@code usr_wallet} 上必然与订单页不自洽。
     */
    public record WalletRow(String userNo, String nickname, BigDecimal balance, BigDecimal bonus,
                            String currency, String updatedAt,
                            long orderCount, BigDecimal orderAmount,
                            long rechargeCount, BigDecimal rechargeAmount) {
    }

    /** C 端钱包总览（C-WA-01 / C-DF-04）：本金 + 赠金 + 押金 + 冻结。 */
    public record WalletOverview(BigDecimal balance, BigDecimal giftBalance,
                                 BigDecimal depositAmount, BigDecimal frozenAmount, String currency) {
    }

    /** 钱包流水行。{@code amount} 带符号（IN 正 / OUT 负）。 */
    public record WalletTxnRow(String txnNo, String type, String direction, String title,
                               BigDecimal amount, String currency, String bizType, String bizNo,
                               String createdAt) {
    }

    /**
     * 充值套餐行，镜像前端 {@code RechargePackage}。
     * {@code markets} 是 CSV（如 {@code "AE,SA"}），由 {@code usr_recharge_pkg_market} 拼回 —— 存的是关联表。
     */
    public record RechargePackageRow(String packageNo, String name, BigDecimal payAmount,
                                     BigDecimal giftAmount, String currency, String markets,
                                     Integer validDays, Integer sortNo, String status,
                                     String archivedAt) {
    }

    /**
     * 充值订单行，镜像前端 {@code RechargeOrder}。
     * <b>{@code pspTxnNo}</b>：前端的 {@code psgTxnNo} 是笔误，全库统一 {@code psp_}（[db-design §6.2]）。
     */
    public record RechargeOrderRow(String rechargeNo, String userNo, String nickname, String packageNo,
                                   BigDecimal payAmount, BigDecimal giftAmount, BigDecimal creditAmount,
                                   String currency, String channelCode, String status,
                                   String createdAt, String paidAt, String pspTxnNo) {
    }

    /** 会员/次卡行，镜像前端 {@code Member}。{@code cardType} 来自所购 {@code mbr_plan}。 */
    public record MemberRow(String userNo, String nickname, String level, Integer points,
                            String cardType, String planNo, String expireAt, String status) {
    }

    /** C 端会员方案行，镜像 c-app {@code Membership}：全部在售方案 + 当前用户是否已开通。 */
    public record MembershipPlanVO(String planNo, String name, java.math.BigDecimal price,
                                   java.util.List<String> benefits, Boolean active, String expireAt) {
    }

    /** 会员方案（C-MB-01 选购页 / 运营端方案维护）。 */
    public record MemberPlanRow(String planNo, String name, String nameEn, String nameAr,
                                String cardType, BigDecimal price, String currency,
                                Integer periodDays, Integer timesTotal, String rights,
                                boolean autoRenew, Integer sortNo, String status) {
    }
}
