package ai.neargo.sharehub.user.marketing.port;

import ai.neargo.sharehub.api.core.port.CouponUsePort;
import ai.neargo.sharehub.user.marketing.entity.CouponTpl;
import ai.neargo.sharehub.user.marketing.entity.UsrCoupon;
import ai.neargo.sharehub.user.marketing.mapper.CouponTplMapper;
import ai.neargo.sharehub.user.marketing.mapper.UsrCouponMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** {@link CouponUsePort} 的本地实现（单体内同进程，拆服务时换成远调即可）。 */
@Component
public class LocalCouponUse implements CouponUsePort {

    private static final String UNUSED = "UNUSED";
    private static final String USED = "USED";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalCouponUse.class);

    private final UsrCouponMapper mapper;
    private final CouponTplMapper tplMapper;

    public LocalCouponUse(UsrCouponMapper mapper, CouponTplMapper tplMapper) {
        this.mapper = mapper;
        this.tplMapper = tplMapper;
    }

    @Override
    public CouponOffer offerOf(String cUserNo, String couponNo, String currency) {
        if (couponNo == null || couponNo.isBlank() || cUserNo == null) return null;
        UsrCoupon c = mapper.selectOne(new LambdaQueryWrapper<UsrCoupon>()
                .eq(UsrCoupon::getCouponNo, couponNo).last("limit 1"));
        // 属主不符按「不存在」处理，不给出「这张券存在但不是你的」这种可枚举的回答
        if (c == null || !cUserNo.equals(c.getCUserNo()) || !UNUSED.equals(c.getStatus())) return null;
        if (expired(c.getExpireAt())) return null;

        CouponTpl tpl = tplMapper.selectOne(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getTplNo, c.getTplNo()).last("limit 1"));
        if (tpl == null) {
            // 券在、模板没了：抵多少无从谈起。**不能按 0 抵扣放过去** —— 那样用户以为用了券
            log.warn("券的模板不存在，本单按无券计价 couponNo={} tplNo={}", couponNo, c.getTplNo());
            return null;
        }
        if (currency != null && tpl.getCurrency() != null && !currency.equals(tpl.getCurrency())) return null;
        return new CouponOffer(couponNo, tpl.getType(), tpl.getValue(), tpl.getThreshold());
    }

    @Override
    public boolean consume(String couponNo, String orderNo) {
        if (couponNo == null || couponNo.isBlank()) return false;
        // 条件更新即裁决：status 仍是 UNUSED 才改得动。两单并发抢同一张券时只有一单拿到 1
        UsrCoupon set = new UsrCoupon();
        set.setStatus(USED);
        set.setUsedOrderNo(orderNo);
        return mapper.update(set, new LambdaUpdateWrapper<UsrCoupon>()
                .eq(UsrCoupon::getCouponNo, couponNo)
                .eq(UsrCoupon::getStatus, UNUSED)) == 1;
    }

    /**
     * {@code expire_at} 是 {@code yyyy-MM-dd} 字符串列。
     *
     * <p>解析不了时返回 <b>false（不过期）</b>：脏数据不该表现为「券凭空作废」——
     * 那是用户看得见、又说不出所以然的损失。只记一条 WARN 等人来修。
     */
    private boolean expired(String expireAt) {
        if (expireAt == null || expireAt.isBlank()) return false;   // 空=不限期
        try {
            return LocalDate.parse(expireAt.substring(0, Math.min(10, expireAt.length())))
                    .isBefore(LocalDate.now());
        } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            log.warn("券有效期字面量无法解析，按不过期处理 expireAt={}", expireAt);
            return false;
        }
    }
}
