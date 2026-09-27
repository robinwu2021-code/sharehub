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
    /**
     * 手工调账入参。
     *
     * <p><b>不复用 {@link WalletRow}</b>：那是读模型，`orderCount` 这类**聚合出来的**字段
     * 在里面是 primitive，请求体里没带就直接 500（Jackson 无法把 null 映射成 long）——
     * 而前端的编辑表单本来就只填余额/赠额。更要紧的是：把聚合字段放进入参，
     * 等于邀请调用方去「设置」一个算出来的数。
     *
     * @param balance 调整后的余额；null = 不动
     * @param bonus   调整后的赠额；null = 不动
     */
    public record WalletAdjustReq(String userNo, BigDecimal balance, BigDecimal bonus, String currency) {
    }

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
    /**
     * 充值套餐**写入参**（白名单）。
     *
     * <p><b>比实体少 {@code archivedAt}</b> —— 归档走
     * {@code /recharge-packages/{no}/archive|unarchive}，盖的是时间戳。
     * 让保存端点也能写它，等于给归档开了第二条不走审计的路。
     *
     * <p>{@code status}（ENABLED/DISABLED）留在入参里：它是套餐的**上下架开关**，
     * 没有专门端点，编辑就是它的正当入口 —— 与「归档」不是一回事。
     *
     * @param payAmount 支付金额，必填且须大于 0 —— 不校验的话能建出一个 0 元充 20 元的套餐
     */
    /**
     * C 端按套餐充值入参（裸 Map → record，2026-09-26）。
     *
     * <p><b>只有 packageNo，刻意不收金额</b>：金额由服务端按套餐算。
     * 收前端传的金额，「充 1 元到账 100」就是一次普通的改参数请求。
     */
    public record RechargeReq(String packageNo) {
    }

    public record RechargePackageReq(String packageNo, String regionId, String name,
                                     BigDecimal payAmount, BigDecimal giftAmount, String currency,
                                     Integer validDays, Integer sortNo, String status,
                                     // 适用市场 CSV（如 "AE,SA"），与读出参 RechargePackageRow.markets 同形状。
                                     // **此前写入面没有这个字段**：运营在「适用市场」勾了国家、保存拿到 200，
                                     // 关联表一行都没写 —— 而 C 端是按用户所在国家筛套餐的，
                                     // 没有市场的套餐对**每个用户都不出现**。必填字段却存不进去，
                                     // 症状是「新建的套餐 C 端看不到」，没人会想到是这一格。
                                     String markets) {
        /** 映射到实体。**archivedAt 有意不设**（见类注释）。 */
        public ai.neargo.sharehub.user.asset.entity.UsrRechargePkg toEntity() {
            if (payAmount == null || payAmount.signum() <= 0) {
                throw new IllegalArgumentException("支付金额必填且须大于 0");
            }
            if (giftAmount != null && giftAmount.signum() < 0) {
                throw new IllegalArgumentException("赠送金额不能为负");
            }
            var e = new ai.neargo.sharehub.user.asset.entity.UsrRechargePkg();
            e.setPackageNo(packageNo);
            e.setRegionId(regionId);
            e.setName(name);
            e.setPayAmount(payAmount);
            e.setGiftAmount(giftAmount);
            e.setCurrency(currency);
            e.setValidDays(validDays);
            e.setSortNo(sortNo);
            e.setStatus(status);
            return e;
        }
    }

    public record RechargePackageRow(String packageNo, String name, BigDecimal payAmount,
                                     BigDecimal giftAmount, String currency, String markets,
                                     Integer validDays, Integer sortNo, String status,
                                     String archivedAt) {
    }

    /**
     * C 端充值结果（{@code POST /mp/user/recharge}）。
     *
     * <p>把**充值后的钱包**一并带回：客户端刚充完就要显示新余额，
     * 分成两次请求的话中间那一瞬显示的是旧数字 —— 用户会以为钱没到账。
     *
     * <p>金额三项都由服务端按套餐算出：{@code creditAmount = payAmount + giftAmount}。
     * <b>前端传的金额一概不采信</b> —— 采信等于把定价权交给调用方。
     */
    public record RechargeResultVO(String rechargeNo, String packageNo,
                                   BigDecimal payAmount, BigDecimal giftAmount, BigDecimal creditAmount,
                                   String currency, String status,
                                   BigDecimal balance, BigDecimal bonus) {
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
                                   String currency, String cardType, PlanRightsVO rights,
                                   Boolean active, String expireAt) {
    }

    /**
     * 方案权益，**结构化而非文案**（[TDD-会员套餐 §2]）。
     *
     * <p>此前这里是 {@code List<String> benefits}，直接把 {@code mbr_plan.rights}
     * 的自由文案透出去。两个后果：
     * <ul>
     *   <li><b>文案跟不上界面语言</b> —— 后端存一句中文，阿语界面上就是一句中文。
     *       {@code FeeItem} 那边已经为同一个错改过一轮（见 c-app {@code types/index.ts}）；</li>
     *   <li><b>权益不可能参与计价</b> —— 自由文本里解析不出折扣率，
     *       于是页面写着「租金九折」而账单照收原价。</li>
     * </ul>
     *
     * @param rentDiscount 折扣率，0.9 = 九折；null = 不打折
     * @param freeMinutes  每单免费分钟数；0 = 无
     * @param depositFree  是否免押
     */
    public record PlanRightsVO(BigDecimal rentDiscount, Integer freeMinutes, Boolean depositFree) {
    }

    /**
     * 会员方案行（运营端方案维护）。
     *
     * <p>{@code rights} 是**结构化对象**而不是 JSON 字符串 ——
     * 透出字符串等于让页面自己解析，而它解析出来的形状与计价链读的形状**没有任何东西保证一致**。
     */
    public record MemberPlanRow(String planNo, String name, String nameEn, String nameAr,
                                String cardType, BigDecimal price, String currency,
                                Integer periodDays, Integer timesTotal, PlanRightsVO rights,
                                boolean autoRenew, Integer sortNo, String status) {
    }

    /**
     * 会员方案保存入参。
     *
     * <p><b>不用实体当请求体</b>（{@code known-entity-request-bodies} 台账：
     * 「新端点不许再用实体当请求体」）—— 实体当请求体时客户端传哪些字段就能改哪些字段，
     * 而那份台账是黑名单，以后给实体加个字段它默认就可写。
     *
     * <p><b>{@code status} 不在入参里</b>：启停走 {@code /enable}|{@code /disable} 动作端点。
     * 让状态混在保存字段里，「停用一个方案」和「改个名字」就成了同一个动作，
     * 而其中一个会影响正在卖的东西 —— 公告那条链刚为同样的错修过一轮。
     *
     * @param planNo 为空则新建（取号 MP），否则按业务键更新
     */
    public record MemberPlanReq(String planNo, String name, String nameEn, String nameAr,
                                String cardType, BigDecimal price, String currency,
                                Integer periodDays, Integer timesTotal, PlanRightsVO rights,
                                Boolean autoRenew, Integer sortNo) {
    }
}
