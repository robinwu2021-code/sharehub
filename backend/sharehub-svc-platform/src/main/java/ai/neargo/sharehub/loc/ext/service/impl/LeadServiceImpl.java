package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadMapper;
import ai.neargo.sharehub.loc.ext.LeadOwnerType;
import ai.neargo.sharehub.loc.ext.SiteAgentRole;
import ai.neargo.sharehub.loc.ext.dto.SiteAgentDtos.SiteAgentRow;
import ai.neargo.sharehub.loc.ext.service.LeadService;
import ai.neargo.sharehub.loc.ext.service.SiteAgentService;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.loc.ext.LeadStateMachine;
import ai.neargo.sharehub.loc.ext.LeadStatus;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadTerms;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * BD 商机实现：属性保存走 {@link AbstractCrudService}；阶段迁移、丢单原因、查重、服务端字段锁在钩子里。
 * 认领 / 转化 / 提醒回收这些动作在 {@code LeadOpsServiceImpl}。
 */
@Service
public class LeadServiceImpl extends AbstractCrudService<LocLead, Lead> implements LeadService {

    private static final String SIGNED = LeadStatus.SIGNED.name();

    private static final Logger log = LoggerFactory.getLogger(LeadServiceImpl.class);

    private final SiteAgentService siteAgents;
    private final LocLeadMapper leads;
    private final LeadStateMachine sm;
    private final SysParamPort params;

    public LeadServiceImpl(LocLeadMapper mapper, SiteAgentService siteAgents, LeadStateMachine sm, SysParamPort params) {
        super(mapper);
        this.siteAgents = siteAgents;
        this.leads = mapper;
        this.sm = sm;
        this.params = params;
    }

    @Override
    protected String keyColumn() {
        return "lead_no";
    }

    @Override
    protected String keyOf(LocLead e) {
        return e.getLeadNo();
    }

    @Override
    protected void setKey(LocLead e, String no) {
        e.setLeadNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.LEAD;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"lead_no", "venue_name", "owner"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"stage", "owner", "ownerType", "regionId", "siteNo", "inPool"};
    }

    @Override
    protected String orderColumn() {
        return "updated_at";
    }

    /**
     * 新建：阶段可以是任一合法值（补录已签的历史商机不是迁移），但落在 LOST 也要原因。
     * 服务端字段一律清掉 —— 新建请求里带 contractNo / inPool 之类只可能是伪造。
     */
    @Override
    protected void beforeCreate(LocLead e) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage(LeadStatus.NEW.name());
        if (e.getExpectSites() == null) e.setExpectSites(0);
        if (e.getOwnerType() == null || e.getOwnerType().isBlank()) e.setOwnerType(LeadOwnerType.STAFF.name());
        if ((e.getOwner() == null || e.getOwner().isBlank()) && LeadOwnerType.STAFF.name().equals(e.getOwnerType())) {
            e.setOwner(SecurityUtils.currentUser().map(ai.neargo.sharehub.auth.LoginUser::userNo).orElse(null));
        }
        LeadStatus stage = checkStage(e.getStage());
        checkOwnerType(e.getOwnerType());
        if (stage == LeadStatus.LOST) requireLostReason(e.getLostReason());
        LocalDateTime now = LocalDateTime.now();
        e.setContractNo(null);
        e.setInPool(0);
        e.setPooledAt(null);
        e.setPrevOwner(null);
        e.setRemindAt(null);
        e.setReactivatedAt(null);
        e.setLastFollowAt(now);
        e.setLostAt(stage == LeadStatus.LOST ? now : null);
        rejectDuplicate(e);
    }

    /**
     * 编辑：阶段变化必须是状态机里的一条边；服务端字段从库取（批量赋值加固）；在池里的商机负责人只能经认领改。
     */
    @Override
    protected void beforeUpdate(LocLead e, LocLead current) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage(current.getStage());
        if (e.getOwnerType() == null || e.getOwnerType().isBlank()) e.setOwnerType(current.getOwnerType());
        LeadStatus to = checkStage(e.getStage());
        checkOwnerType(e.getOwnerType());
        String event = sm.check(current.getStage(), to.name());
        e.setContractNo(current.getContractNo());
        e.setInPool(current.getInPool());
        e.setPooledAt(current.getPooledAt());
        e.setPrevOwner(current.getPrevOwner());
        e.setRemindAt(current.getRemindAt());
        e.setLastFollowAt(current.getLastFollowAt());
        e.setLostAt(current.getLostAt());
        e.setReactivatedAt(current.getReactivatedAt());
        if (current.getInPool() != null && current.getInPool() == 1) {
            e.setOwner(current.getOwner());
            e.setOwnerType(current.getOwnerType());
        }
        if (event == null) {
            // 未迁移：丢单原因只在迁到 LOST 时写，平时编辑不许改掉
            e.setLostReason(current.getLostReason());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (to == LeadStatus.LOST) {
            requireLostReason(e.getLostReason());
            e.setLostAt(now);
        } else {
            e.setLostReason(current.getLostReason());   // 重新激活保留上次丢单原因作历史
        }
        if (to == LeadStatus.NEW && LeadStatus.LOST.name().equals(current.getStage())) e.setReactivatedAt(now);
        e.setLastFollowAt(now);   // 推进阶段本身就是一次跟进
        log.info("商机阶段 {} {} → {}（{}）", current.getLeadNo(), current.getStage(), to, event);
    }

    /**
     * 查重（对齐清单 B6）：同一场地名或地址，{@code lead.dedup.days}（默认 90）天内有<b>别人</b>在跟 → 拒绝，
     * 并告诉他现在是谁在跟。自己重复录不拦（可能是补录），已丢单与在池里的不算「在跟」。
     *
     * <p>豁免数据范围：BD 通常只看得到自己的商机，按他能看到的查重等于没查。
     */
    private void rejectDuplicate(LocLead e) {
        String name = norm(e.getVenueName());
        String address = norm(e.getAddress());
        if (name == null && address == null) return;
        LocalDateTime since = LocalDateTime.now().minusDays(params.intOf("lead.dedup.days", 90));
        List<LocLead> hits = DataScopeContext.executeWithoutScope(() -> leads.selectList(new LambdaQueryWrapper<LocLead>()
                .ne(LocLead::getStage, LeadStatus.LOST.name())
                .and(x -> x.eq(LocLead::getInPool, 0).or().isNull(LocLead::getInPool))
                .ge(LocLead::getLastFollowAt, since)
                .and(x -> {
                    if (name != null) x.apply("LOWER(TRIM(venue_name)) = {0}", name);
                    if (name != null && address != null) x.or();
                    if (address != null) x.apply("LOWER(TRIM(address)) = {0}", address);
                })
                .last("limit 5")));
        for (LocLead h : hits) {
            if (h.getOwner() != null && !h.getOwner().equals(e.getOwner())) {
                throw BizException.conflict("error.lead.duplicate", h.getLeadNo(), h.getOwner());
            }
        }
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireLostReason(String reason) {
        if (reason == null || reason.isBlank()) throw BizException.badRequest("error.lead.lost_reason_required");
    }

    /**
     * 保存后把拓展归因落成一行 {@code loc_site_agent(role=DEVELOP)}。
     *
     * <p><b>为什么覆写 save 而不是给基类加 afterSave 钩子</b>：那个基类有 15 个使用者，
     * 为一个域的需要给所有人加一个生命周期点，代价不成比例。
     */
    @Override
    public Lead save(LocLead body) {
        Lead vo = super.save(body);
        writeDevelopResponsibility(vo);
        return vo;
    }

    /**
     * 签下且归属是伙伴时，把「这个场地是谁谈下来的」变成一行拓展责任 —— 拓展佣金的依据。
     *
     * <p><b>为什么条件是「已签 + 已指定站点」而不是「阶段翻成 SIGNED 的那一刻」</b>：
     * 商机常常是**先签下、后建站**，签的那一刻还没有站点可挂。站点补填上去的那一次保存
     * 同样会走到这里补写 —— 幂等由 {@code upsert} 的唯一键保证，重复保存不会多出行。
     *
     * <p><b>写不成不让保存失败</b>：商机保存是 BD 的日常动作，归因是它的副产物。
     * 副产物失败把主动作一起回滚，BD 会以为商机没存上而重填一遍，
     * 而真正该被看见的是日志里那条告警。
     */
    @Override
    public void writeAttribution(String leadNo) {
        writeDevelopResponsibility(get(leadNo));
    }

    private void writeDevelopResponsibility(Lead vo) {
        if (!SIGNED.equals(vo.stage()) || !LeadOwnerType.AGENT.name().equals(vo.ownerType())) return;
        if (vo.siteNo() == null || vo.siteNo().isBlank()) {
            log.info("商机 {} 已签且归属伙伴 {}，但还没指定落成站点 —— 拓展责任行等站点填上再写",
                    vo.leadNo(), vo.owner());
            return;
        }
        if (vo.owner() == null || vo.owner().isBlank()) {
            log.warn("商机 {} 归属类型是伙伴却没有归属方编号，无法写拓展责任行", vo.leadNo());
            return;
        }
        try {
            siteAgents.upsert(vo.siteNo(), new SiteAgentRow(null, vo.siteNo(), vo.owner(),
                    null, null, SiteAgentRole.DEVELOP.name(), null, null, null, null,
                    "来自商机 " + vo.leadNo() + "：" + vo.venueName()));
        } catch (RuntimeException ex) {
            // 最常见的是「该伙伴在本站点已有牵线，与拓展互斥」—— 那是真实的业务冲突，
            // 要让运营看见并自己决定算哪一个，而不是默默把商机也存不上。
            log.warn("商机 {} 的拓展责任行没写成（站点 {} / 伙伴 {}）：{}",
                    vo.leadNo(), vo.siteNo(), vo.owner(), ex.getMessage());
        }
    }

    private static LeadStatus checkStage(String stage) {
        return LeadStatus.of(stage).orElseThrow(() -> BizException.badRequest("error.lead.stage_invalid", stage));
    }

    private static void checkOwnerType(String ownerType) {
        if (ownerType != null && LeadOwnerType.of(ownerType).isEmpty()) {
            throw BizException.badRequest("error.lead.owner_type_invalid", ownerType);
        }
    }

    @Override
    protected Lead toVO(LocLead e) {
        return new Lead(e.getLeadNo(), e.getVenueName(), e.getContact(), e.getStage(),
                e.getOwner(), e.getOwnerType(), e.getSiteNo(),
                e.getExpectSites(), e.getNextFollowAt(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString(),
                e.getAddress(), e.getVenueNo(), e.getContractNo(), e.getLostReason(), e.getLostAt(), e.getLastFollowAt(),
                e.getInPool() != null && e.getInPool() == 1, e.getPrevOwner(), e.getCompetitorName(),
                e.getCompetitorExclusiveUntil(), e.getReactivatedAt(),
                new LeadTerms(e.getShareMode(), e.getShareRate(), e.getEntryFee(), e.getGuaranteeAmount(),
                        e.getTermMonths(), e.getExclusiveFlag()));
    }
}
