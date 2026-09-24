package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.OnboardingReviewReq;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.VenueOnboarding;
import ai.neargo.sharehub.loc.ext.entity.LocVenueOnboarding;
import ai.neargo.sharehub.loc.ext.mapper.LocVenueOnboardingMapper;
import ai.neargo.sharehub.loc.ext.service.VenueCreator;
import ai.neargo.sharehub.loc.ext.service.VenueOnboardingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店进件审核实现。
 *
 * <p>状态迁移只有两条且都从 {@code PENDING} 出发：{@code PENDING→APPROVED}、{@code PENDING→REJECTED}。
 * 规则简单到不值得再引一个状态机组件，直接显式校验；非法迁移抛 {@link IllegalArgumentException}
 * （全局兜底映射为 400，[common/GlobalExceptionHandler]）。
 */
@Service
public class VenueOnboardingServiceImpl implements VenueOnboardingService {

    /** 唯一可改内容的状态；也是审核的唯一入态。 */
    private static final String PENDING = "PENDING";

    private final LocVenueOnboardingMapper mapper;
    private final VenueCreator venueCreator;

    public VenueOnboardingServiceImpl(LocVenueOnboardingMapper mapper, VenueCreator venueCreator) {
        this.mapper = mapper;
        this.venueCreator = venueCreator;
    }

    @Override
    public PageResult<VenueOnboarding> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<LocVenueOnboarding> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(LocVenueOnboarding::getOnboardingNo, keyword)
                    .or().like(LocVenueOnboarding::getVenueName, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(LocVenueOnboarding::getStatus, status);
        w.orderByDesc(LocVenueOnboarding::getId);

        Page<LocVenueOnboarding> r = mapper.selectPage(new Page<>(p, s), w);
        List<VenueOnboarding> rows = r.getRecords().stream().map(VenueOnboardingServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public VenueOnboarding get(String onboardingNo) {
        LocVenueOnboarding e = byNo(onboardingNo);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional
    public VenueOnboarding save(String onboardingNo, VenueOnboarding in) {
        if (in == null || in.venueName() == null || in.venueName().isBlank()) {
            throw new IllegalArgumentException("门店名称必填");
        }
        LocVenueOnboarding e = (onboardingNo == null || onboardingNo.isBlank()) ? null : byNo(onboardingNo);
        if (e != null && !PENDING.equals(e.getStatus())) {
            // 审核结论是对「当时那份内容」做的。事后改内容，结论就对不上它审过的东西了。
            throw new IllegalArgumentException("进件已审核，内容不可再改: " + onboardingNo
                    + " status=" + e.getStatus());
        }
        boolean create = e == null;
        if (create) {
            e = new LocVenueOnboarding();
            e.setOnboardingNo(ai.neargo.common.core.IdGenerator.next(
                    ai.neargo.sharehub.common.BizKey.ONBOARDING));
            e.setTenantId("MAIN");
            e.setStatus(PENDING);
            // 提交时刻由服务端定。让客户端传的话，补录的单子可以被写成一个月前提交的，
            // 而进件的 SLA 正是按这个时刻算的。
            e.setRequestedAt(LocalDateTime.now().toString());
        }
        e.setVenueName(in.venueName().trim());
        e.setContact(in.contact());
        e.setIndustry(in.industry());
        if (create) mapper.insert(e); else mapper.updateById(e);
        return toVO(byNo(e.getOnboardingNo()));
    }

    @Override
    @Transactional
    public VenueOnboarding review(String onboardingNo, OnboardingReviewReq req) {
        LocVenueOnboarding e = byNo(onboardingNo);
        if (e == null) throw new IllegalArgumentException("进件不存在: " + onboardingNo);
        if (!PENDING.equals(e.getStatus())) {
            throw new IllegalArgumentException("进件已审核，不可重复审核: " + onboardingNo + " status=" + e.getStatus());
        }
        if (req == null || req.approve() == null) {
            throw new IllegalArgumentException("审核结论 approve 必填");
        }

        boolean approve = req.approve();
        if (!approve && (req.note() == null || req.note().isBlank())) {
            throw new IllegalArgumentException("驳回必须填写原因");
        }

        if (approve) {
            // 通过 → 先建场地方再回填 venue_no（[api/README §3.4]）。
            // 建场地方走 VenueCreator 接缝：loc_venue 的 mapper 属 loc 主包，本分片不得直接触碰。
            // 与本次更新同一事务：建方成功但回填失败会导致「场地方孤儿」，必须一起回滚。
            e.setVenueNo(venueCreator.create(e.getVenueName(), e.getContact(), e.getIndustry()));
            e.setStatus("APPROVED");
        } else {
            e.setStatus("REJECTED");
        }
        e.setReviewNote(req.note());
        e.setReviewBy(req.reviewBy());
        e.setReviewAt(LocalDateTime.now().toString());
        mapper.updateById(e);
        return toVO(e);
    }

    private LocVenueOnboarding byNo(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<LocVenueOnboarding>()
                .eq(LocVenueOnboarding::getOnboardingNo, no).last("limit 1"));
    }

    private static VenueOnboarding toVO(LocVenueOnboarding e) {
        return new VenueOnboarding(e.getOnboardingNo(), e.getVenueName(), e.getContact(),
                e.getIndustry(), e.getRequestedAt(), e.getStatus(),
                e.getReviewAt(), e.getReviewNote(), e.getVenueNo());
    }
}
