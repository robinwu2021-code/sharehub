package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralVO;
import ai.neargo.sharehub.user.marketing.entity.MktReferral;
import ai.neargo.sharehub.user.marketing.mapper.MktReferralMapper;
import ai.neargo.sharehub.user.marketing.service.ReferralService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** 邀请裂变实现（只读）。 */
@Service
public class ReferralServiceImpl implements ReferralService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MktReferralMapper mapper;
    private final ai.neargo.sharehub.user.marketing.mapper.MktReferralRuleMapper ruleMapper;

    public ReferralServiceImpl(MktReferralMapper mapper,
                               ai.neargo.sharehub.user.marketing.mapper.MktReferralRuleMapper ruleMapper) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
    }

    @Override
    public PageResult<ReferralVO> page(Integer page, Integer size, String keyword, String inviterNo, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<MktReferral> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(MktReferral::getInviteNo, keyword)
                    .or().like(MktReferral::getInviterNo, keyword)
                    .or().like(MktReferral::getInviteeNo, keyword));
        }
        w.eq(inviterNo != null && !inviterNo.isBlank(), MktReferral::getInviterNo, inviterNo);
        w.eq(status != null && !status.isBlank(), MktReferral::getStatus, status);
        w.orderByDesc(MktReferral::getId);

        Page<MktReferral> r = mapper.selectPage(new Page<>(p, s), w);
        List<ReferralVO> rows = r.getRecords().stream().map(ReferralServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static ReferralVO toVO(MktReferral e) {
        return new ReferralVO(e.getInviteNo(), e.getInviterNo(), e.getInviteeNo(),
                e.getReward(), e.getCurrency(), e.getStatus(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
    }

    @Override
    public ai.neargo.common.core.PageResult<ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO>
            pageRules(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        var r = ruleMapper.selectPage(new Page<>(p, s),
                new LambdaQueryWrapper<ai.neargo.sharehub.user.marketing.entity.MktReferralRule>()
                        .isNull(ai.neargo.sharehub.user.marketing.entity.MktReferralRule::getArchivedAt)
                        .orderByDesc(ai.neargo.sharehub.user.marketing.entity.MktReferralRule::getId));
        var rows = r.getRecords().stream().map(e ->
                new ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO(
                        e.getRuleNo(), e.getName(), e.getRewardTo(), e.getInviterReward(),
                        e.getCurrency(), e.getTriggerEvent(), e.getCapPerUser(),
                        e.getStartAt() == null ? null : e.getStartAt().toString(),
                        e.getEndAt() == null ? null : e.getEndAt().toString(),
                        "ENABLED".equals(e.getStatus()) ? "ACTIVE" : "DISABLED")).toList();
        return new ai.neargo.common.core.PageResult<>(rows, r.getTotal());
    }
}
