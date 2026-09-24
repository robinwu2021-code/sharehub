package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralVO;
import ai.neargo.sharehub.user.marketing.entity.MktReferral;
import ai.neargo.sharehub.user.marketing.mapper.MktReferralMapper;
import ai.neargo.sharehub.user.marketing.service.ReferralService;
import ai.neargo.sharehub.user.marketing.entity.MktReferralRule;
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

    /** 库里的启用态。出参映射成 ACTIVE（见 pageRules），两边别混用。 */
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";
    /** 出参口径（前端契约）：库里的 ENABLED/其它 → ACTIVE/DISABLED。两套词表别混用。 */
    private static final String VO_ACTIVE = "ACTIVE";
    private static final String VO_DISABLED = "DISABLED";

    /** 奖励对象取值域。脏值会让发奖时谁都匹配不上，而那是静默不发奖。 */
    private static final java.util.Set<String> REWARD_TO = java.util.Set.of("INVITER", "INVITEE", "BOTH");
    /** 触发时机取值域。 */
    private static final java.util.Set<String> TRIGGERS = java.util.Set.of("REGISTERED", "FIRST_ORDER", "FIRST_PAID");

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO saveRule(
            String ruleNo, ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO in) {
        if (in == null || in.name() == null || in.name().isBlank()) {
            throw new IllegalArgumentException("规则名称必填");
        }
        if (in.rewardTo() != null && !REWARD_TO.contains(in.rewardTo())) {
            throw new IllegalArgumentException("奖励对象非法: " + in.rewardTo());
        }
        if (in.trigger() != null && !TRIGGERS.contains(in.trigger())) {
            throw new IllegalArgumentException("触发时机非法: " + in.trigger());
        }
        if (in.rewardAmount() != null && in.rewardAmount().signum() < 0) {
            throw new IllegalArgumentException("奖励金额不能为负");
        }
        java.time.LocalDateTime from = parseTs(in.startAt()), to = parseTs(in.endAt());
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("结束时间不能早于开始时间");
        }

        MktReferralRule e = (ruleNo == null || ruleNo.isBlank()) ? null
                : ruleMapper.selectOne(new LambdaQueryWrapper<MktReferralRule>()
                        .eq(MktReferralRule::getRuleNo, ruleNo).last("limit 1"));
        boolean create = e == null;
        if (create) {
            e = new MktReferralRule();
            e.setRuleNo(ai.neargo.common.core.IdGenerator.next(ai.neargo.sharehub.common.BizKey.REFERRAL_RULE));
            e.setTenantId("MAIN");
        }
        e.setName(in.name().trim());
        if (in.rewardTo() != null) e.setRewardTo(in.rewardTo());
        if (in.rewardAmount() != null) e.setInviterReward(in.rewardAmount());
        if (in.currency() != null && !in.currency().isBlank()) e.setCurrency(in.currency());
        if (in.trigger() != null) e.setTriggerEvent(in.trigger());
        if (in.maxPerInviter() != null) e.setCapPerUser(in.maxPerInviter());
        e.setStartAt(from);
        e.setEndAt(to);
        // ⚠️ 出参把 ENABLED 映射成 ACTIVE（见 pageRules），入参要映射回去。
        // 直接把 "ACTIVE" 写进库的话，列表会把它显示成「已停用」—— 存了、看着却是反的。
        if (in.status() != null && !in.status().isBlank()) {
            e.setStatus(VO_ACTIVE.equals(in.status()) || ENABLED.equals(in.status()) ? ENABLED : DISABLED);
        } else if (create) {
            e.setStatus(ENABLED);
        }
        if (create) ruleMapper.insert(e); else ruleMapper.updateById(e);

        MktReferralRule saved = ruleMapper.selectOne(new LambdaQueryWrapper<MktReferralRule>()
                .eq(MktReferralRule::getRuleNo, e.getRuleNo()).last("limit 1"));
        return new ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO(
                saved.getRuleNo(), saved.getName(), saved.getRewardTo(), saved.getInviterReward(),
                saved.getCurrency(), saved.getTriggerEvent(), saved.getCapPerUser(),
                saved.getStartAt() == null ? null : saved.getStartAt().toString(),
                saved.getEndAt() == null ? null : saved.getEndAt().toString(),
                ENABLED.equals(saved.getStatus()) ? VO_ACTIVE : VO_DISABLED);
    }

    /** 宽松解析：接受 `yyyy-MM-dd`、`yyyy-MM-ddTHH:mm:ss` 与空格分隔三种，空串当 null。 */
    private static java.time.LocalDateTime parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        if (v.length() == 10) return java.time.LocalDate.parse(v).atStartOfDay();
        if (v.length() > 10 && v.charAt(10) == ' ') v = v.substring(0, 10) + "T" + v.substring(11);
        return java.time.LocalDateTime.parse(v);
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
                        ENABLED.equals(e.getStatus()) ? VO_ACTIVE : VO_DISABLED)).toList();
        return new ai.neargo.common.core.PageResult<>(rows, r.getTotal());
    }
}
