package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ClaimableCouponVO;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.UserCouponVO;
import ai.neargo.sharehub.user.marketing.entity.CouponTpl;
import ai.neargo.sharehub.user.marketing.entity.UsrCoupon;
import ai.neargo.sharehub.user.marketing.mapper.CouponTplMapper;
import ai.neargo.sharehub.user.marketing.mapper.UsrCouponMapper;
import ai.neargo.sharehub.user.marketing.service.UserCouponService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户券实现 —— 聚合根手写风格（{@code LambdaQueryWrapper}，非法操作显式抛异常）。
 *
 * <p>取号并发说明同 {@code AbstractCrudService#nextNo}：扫描同前缀最大号 +1，
 * 并发撞号靠 {@code uk_coupon_no} 兜底，调用方按 DuplicateKey 重试。
 */
@Service
public class UserCouponServiceImpl implements UserCouponService {

    private static final String TENANT_MAIN = "MAIN";

    private final UsrCouponMapper mapper;
    private final ai.neargo.sharehub.user.member.mapper.UsrCouponIssueMapper couponIssueMapper;
    private final CouponTplMapper tplMapper;

    public UserCouponServiceImpl(UsrCouponMapper mapper, CouponTplMapper tplMapper,
                                 ai.neargo.sharehub.user.member.mapper.UsrCouponIssueMapper couponIssueMapper) {
        this.couponIssueMapper = couponIssueMapper;
        this.mapper = mapper;
        this.tplMapper = tplMapper;
    }

    @Override
    public PageResult<UserCouponVO> page(Integer page, Integer size, String cUserNo, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrCoupon> w = new LambdaQueryWrapper<UsrCoupon>()
                // 属主过滤：C 端场景由调用方传入会话里的 cUserNo，绝不接受前端传参
                .eq(cUserNo != null && !cUserNo.isBlank(), UsrCoupon::getCUserNo, cUserNo)
                .eq(status != null && !status.isBlank(), UsrCoupon::getStatus, status)
                .orderByDesc(UsrCoupon::getId);

        Page<UsrCoupon> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, CouponTpl> tpls = loadTpls(r.getRecords());
        List<UserCouponVO> rows = r.getRecords().stream().map(e -> toVO(e, tpls.get(e.getTplNo()))).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    @Transactional
    public List<UserCouponVO> issue(String tplNo, List<String> cUserNos, String expireAt) {
        CouponTpl tpl = requireActiveTpl(tplNo);
        if (cUserNos == null || cUserNos.isEmpty()) return List.of();

        int stock = tpl.getStock() == null ? 0 : tpl.getStock();
        int issued = tpl.getIssued() == null ? 0 : tpl.getIssued();
        // stock=0 按 db-design §6.3 语义是「不限量」；否则按剩余量截断而不是整批失败
        int remaining = stock == 0 ? Integer.MAX_VALUE : stock - issued;
        if (remaining <= 0) throw new IllegalStateException("券模板库存已发完: " + tplNo);

        List<UserCouponVO> out = new ArrayList<>();
        for (String userNo : cUserNos) {
            if (out.size() >= remaining) break;
            if (userNo == null || userNo.isBlank()) continue;
            UsrCoupon exist = findUnused(userNo, tplNo);
            if (exist != null) continue; // 防重复发：该用户对该模板已有未使用券
            out.add(toVO(insert(userNo, tpl, expireAt), tpl));
        }

        if (!out.isEmpty()) {
            tpl.setIssued(issued + out.size());
            tplMapper.updateById(tpl);
        }
        return out;
    }

    @Override
    public List<ClaimableCouponVO> claimable(String cUserNo) {
        List<CouponTpl> tpls = tplMapper.selectList(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getStatus, "ACTIVE")
                .isNull(CouponTpl::getArchivedAt)
                .orderByDesc(CouponTpl::getId));

        // 已领过哪些模板：一次查完，不要逐行 findUnused（列表长了就是 N+1）
        java.util.Set<String> mine = tpls.isEmpty() ? java.util.Set.of()
                : mapper.selectList(new LambdaQueryWrapper<UsrCoupon>()
                        .eq(UsrCoupon::getCUserNo, cUserNo)
                        .eq(UsrCoupon::getStatus, "UNUSED")
                        .in(UsrCoupon::getTplNo, tpls.stream().map(CouponTpl::getTplNo).toList()))
                .stream().map(UsrCoupon::getTplNo).collect(java.util.stream.Collectors.toSet());

        List<ClaimableCouponVO> out = new ArrayList<>();
        for (CouponTpl t : tpls) {
            int stock = t.getStock() == null ? 0 : t.getStock();
            int issued = t.getIssued() == null ? 0 : t.getIssued();
            boolean claimed = mine.contains(t.getTplNo());
            // 发完的模板不再展示（已领过的除外 —— 那张券还在用户手里，列表里消失会让人以为券丢了）
            if (stock != 0 && issued >= stock && !claimed) continue;
            Integer remaining = stock == 0 ? null : Math.max(0, stock - issued);
            out.add(new ClaimableCouponVO(t.getTplNo(), t.getName(), t.getType(),
                    t.getValue(), t.getThreshold(), t.getCurrency(), remaining, claimed));
        }
        return out;
    }

    @Override
    @Transactional
    public UserCouponVO claim(String cUserNo, String tplNo) {
        CouponTpl tpl = requireActiveTpl(tplNo);

        UsrCoupon exist = findUnused(cUserNo, tplNo);
        if (exist != null) return toVO(exist, tpl); // 幂等：重复点「领取」返回已有券

        int stock = tpl.getStock() == null ? 0 : tpl.getStock();
        int issued = tpl.getIssued() == null ? 0 : tpl.getIssued();
        if (stock != 0 && issued >= stock) throw new IllegalStateException("券已领完: " + tplNo);

        UsrCoupon e = insert(cUserNo, tpl, null);
        tpl.setIssued(issued + 1);
        tplMapper.updateById(tpl);
        return toVO(e, tpl);
    }

    // ——————————————————————— 内部 ———————————————————————

    private CouponTpl requireActiveTpl(String tplNo) {
        CouponTpl tpl = tplMapper.selectOne(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getTplNo, tplNo).last("limit 1"));
        if (tpl == null) throw new IllegalArgumentException("券模板不存在: " + tplNo);
        if (!"ACTIVE".equals(tpl.getStatus())) throw new IllegalStateException("券模板已停用: " + tplNo);
        return tpl;
    }

    private UsrCoupon findUnused(String cUserNo, String tplNo) {
        return mapper.selectOne(new LambdaQueryWrapper<UsrCoupon>()
                .eq(UsrCoupon::getCUserNo, cUserNo)
                .eq(UsrCoupon::getTplNo, tplNo)
                .eq(UsrCoupon::getStatus, "UNUSED")
                .last("limit 1"));
    }

    private UsrCoupon insert(String cUserNo, CouponTpl tpl, String expireAt) {
        UsrCoupon e = new UsrCoupon();
        e.setCouponNo(nextCouponNo());
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setTplNo(tpl.getTplNo());
        e.setStatus("UNUSED");
        e.setExpireAt(expireAt);
        mapper.insert(e);
        return e;
    }

    /** 扫描同前缀最大号 +1（[db-design §1.4.1]）；禁止「前缀 + 数组长度」。 */
    private String nextCouponNo() {
        UsrCoupon top = mapper.selectOne(new LambdaQueryWrapper<UsrCoupon>()
                .likeRight(UsrCoupon::getCouponNo, BizKey.COUPON)
                .orderByDesc(UsrCoupon::getCouponNo)
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getCouponNo() != null && top.getCouponNo().length() > BizKey.COUPON.length()) {
            String digits = top.getCouponNo().substring(BizKey.COUPON.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号，由 UNIQUE 兜底
                }
            }
        }
        return BizKey.COUPON + String.format("%06d", n + 1);
    }

    private Map<String, CouponTpl> loadTpls(List<UsrCoupon> rows) {
        Map<String, CouponTpl> m = new HashMap<>();
        List<String> nos = rows.stream().map(UsrCoupon::getTplNo).filter(java.util.Objects::nonNull).distinct().toList();
        if (nos.isEmpty()) return m;
        // 一次 IN 查完，避免逐行回查模板（N+1）
        tplMapper.selectList(new LambdaQueryWrapper<CouponTpl>().in(CouponTpl::getTplNo, nos))
                .forEach(t -> m.put(t.getTplNo(), t));
        return m;
    }

    private static UserCouponVO toVO(UsrCoupon e, CouponTpl tpl) {
        return new UserCouponVO(e.getCouponNo(), e.getCUserNo(), e.getTplNo(),
                tpl == null ? null : tpl.getName(),
                tpl == null ? null : tpl.getType(),
                tpl == null ? null : tpl.getValue(),
                tpl == null ? null : tpl.getThreshold(),
                tpl == null ? null : tpl.getCurrency(),
                e.getStatus(), e.getUsedOrderNo(), e.getExpireAt());
    }

    @Override
    public ai.neargo.common.core.PageResult<?> pageIssueRecords(Integer page, Integer size, String couponNo) {
        int p = (page == null || page < 1) ? 1 : page;
        int sz = (size == null || size < 1) ? 10 : Math.min(size, 200);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                ai.neargo.sharehub.user.member.entity.UsrCouponIssue> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (couponNo != null && !couponNo.isBlank()) {
            w.eq(ai.neargo.sharehub.user.member.entity.UsrCouponIssue::getCouponNo, couponNo);
        }
        w.orderByDesc(ai.neargo.sharehub.user.member.entity.UsrCouponIssue::getId);
        var r = couponIssueMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, sz), w);
        var rows = r.getRecords().stream().map(e ->
                new ai.neargo.sharehub.user.member.dto.MemberDtos.CouponIssueRecord(
                        e.getIssueNo(), e.getCouponNo(), e.getCouponName(), e.getTargetType(),
                        e.getTargetDesc(), e.getQuantity(), e.getOperatorName(),
                        e.getCreatedAt() == null ? null : e.getCreatedAt().toString())).toList();
        return new ai.neargo.common.core.PageResult<>(rows, r.getTotal());
    }
}
