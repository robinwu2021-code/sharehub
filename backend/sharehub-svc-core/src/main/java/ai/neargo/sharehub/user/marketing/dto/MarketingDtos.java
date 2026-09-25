package ai.neargo.sharehub.user.marketing.dto;

import java.math.BigDecimal;

/**
 * user/marketing 子域出参 VO。
 *
 * <p><b>约定</b>：域内 dto 文件，不往顶层 {@code dto/Dto.java} 追加（那个文件已冻结）。
 * 字段镜像 ops-web {@code lib/types/marketing.ts} 的同名 interface。
 */
public final class MarketingDtos {

    private MarketingDtos() {
    }

    /**
     * 券模板行，镜像前端 {@code Coupon}（前端把「券模板」直接叫 Coupon）。
     *
     * <p>比前端多 {@code currency}：[db-design §1.5] 要求金额必带币种，前端缺该字段是已知待补。
     *
     * <p><b>注意 {@code couponNo} 这里装的是 {@code tpl_no}</b> —— 前端字段名如此，为不改前端而保留。
     * 用户手里那张券的号在 {@link UserCouponVO#couponNo()}，两者不是一个东西。
     */
    /**
     * 券模板**写入参**（白名单）。
     *
     * <p>比实体少两个，都有专门入口：
     * <ul>
     *   <li>{@code issued} 已发放数 —— 由发券动作维护。service 的 beforeCreate/beforeUpdate
     *       本来就锁着它（「已发放数不可被编辑覆盖」），这里不声明是**第二道**：
     *       锁是黑名单，得有人记得写；不声明是白名单，新人照抄也漏不掉；</li>
     *   <li>{@code archivedAt} —— 归档走 /coupons/{no}/archive|unarchive。</li>
     * </ul>
     */
    public record CouponTplReq(String tplNo, String name, String type,
                               BigDecimal value, BigDecimal threshold, String currency,
                               String validRule, Integer stock, String status) {
        /** 映射到实体。**issued / archivedAt 有意不设**（见类注释）。 */
        public ai.neargo.sharehub.user.marketing.entity.CouponTpl toEntity() {
            var e = new ai.neargo.sharehub.user.marketing.entity.CouponTpl();
            e.setTplNo(tplNo);
            e.setName(name);
            e.setType(type);
            e.setValue(value);
            e.setThreshold(threshold);
            e.setCurrency(currency);
            e.setValidRule(validRule);
            e.setStock(stock);
            e.setStatus(status);
            return e;
        }
    }

    public record CouponTplVO(String couponNo, String name, String type,
                              BigDecimal value, BigDecimal threshold, String currency,
                              Integer stock, Integer issued, String status,
                              String archivedAt) {
    }

    /**
     * 领券中心的一行（{@code GET /mp/user/coupons/claimable}）—— **可领的券模板**，不是用户手里的券。
     *
     * <p>键叫 {@code tplNo} 而不是 {@code couponNo}：领券要传的是模板号，
     * 而 C 端同时还有一份 {@link UserCouponVO} 列表，那里的 {@code couponNo} 是券实例号（{@code CP…}，见 {@code BizKey.COUPON}）。
     * 两个列表都摆在券页面上，键名一样就一定有人传错 —— 传错的结果是 400「券模板不存在」，
     * 看起来像数据问题，其实是传了另一张表的主键。
     *
     * <p>{@code remaining} 为 null 表示**不限量**（coupon_tpl.stock=0 的语义，[db-design §6.3]），
     * 不是「剩 0 张」；{@code claimed}=true 表示当前用户已有该模板的未使用券，
     * 按钮该置灰 —— 后端 claim 是幂等的，但让用户点了才知道「你已经领过」是糟糕的交互。
     */
    public record ClaimableCouponVO(String tplNo, String name, String type,
                                    BigDecimal value, BigDecimal threshold, String currency,
                                    Integer remaining, boolean claimed) {
    }

    /**
     * 用户手里的券（usr_coupon）。运营端按 {@code cUserNo} 查某人券包，C 端只能查自己的。
     * {@code tpl*} 是模板快照，免得 C 端券包列表逐行回查模板。
     */
    public record UserCouponVO(String couponNo, String cUserNo, String tplNo,
                               String tplName, String tplType,
                               BigDecimal value, BigDecimal threshold, String currency,
                               String status, String usedOrderNo, String expireAt) {
    }

    /** 公告行，镜像前端 {@code Notice}。三语三列全出，前端按当前语种取值。 */
    public record NoticeVO(String noticeNo,
                           String title, String titleEn, String titleAr,
                           String content, String contentEn, String contentAr,
                           String type, boolean pinned,
                           String startAt, String endAt,
                           String status, String publishedBy, String createdAt,
                           String archivedAt) {
    }

    /** 营销活动行，镜像前端 {@code Campaign}。 */
    public record CampaignVO(String campaignNo, String name, String kind, String rule,
                             String status, String startAt, String endAt) {
    }

    /** 推送触达行，镜像前端 {@code PushMessage}。 */
    /**
     * 推送消息行。
     *
     * <p><b>content 必须带出来</b>：前端类型里它是必填，不回就是 undefined —— 列表里那一列空白。
     * 实体本来就有这一列，只是这个 DTO 漏了。
     *
     * <p>⚠️ 前端还期望 `audienceType` / `audienceValue` / `scheduledAt` /
     * `targetCount` / `successCount` / `idempotencyKey` / `operatorName` ——
     * **这些列实体里根本没有**，属于「前端先行、后端未建」，要补得先加列 + 补发送侧统计，
     * 不是对齐能解决的。补之前它们在真后端下一律 undefined。
     */
    /**
     * 推送行，镜像前端 {@code PushMessage}。
     *
     * <p>V75 起补齐排期/触达/幂等/人群六项：此前前端界面正在渲染的
     * {@code operatorName}/{@code targetCount}/{@code successCount}/{@code scheduledAt}
     * 后端根本不返回（**存储也没建过**），表现是「操作人」列全空、
     * 「目标 undefined 人 / 成功 undefined 人」。
     *
     * <p>{@code audience} 是派生的可读标签，{@code audienceType}/{@code audienceValue}
     * 才是权威值 —— 编辑时要靠后两者回填下拉。
     */
    public record PushMessageVO(String pushNo, String title, String content, String channel,
                                String audience, String audienceType, String audienceValue,
                                Integer targetCount, Integer successCount, Integer sentCount,
                                String status, String scheduledAt, String sentAt,
                                String operatorName, String idempotencyKey) {
    }

    /**
     * 裂变邀请行，镜像前端 {@code Referral}。
     * 前端 {@code inviter}/{@code invitee} 是展示名，此处直出 c_user_no —— 昵称由 user 域聚合，
     * 本域不跨表查（避免 N+1），保持 {@code _no} 权威。
     */
    public record ReferralVO(String inviteNo, String inviter, String invitee,
                             BigDecimal reward, String currency, String status, String createdAt) {
    }

    /** 邀请规则行，镜像前端 {@code ReferralRule}（marketing.ts，D-3 补表后的真规则出参）。 */
    public record ReferralRuleVO(String ruleNo, String name, String rewardTo, BigDecimal rewardAmount,
                                 String currency, String trigger, Integer maxPerInviter,
                                 String startAt, String endAt, String status) {
    }
}
