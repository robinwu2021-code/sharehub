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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/** BD 商机实现：全部行为来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class LeadServiceImpl extends AbstractCrudService<LocLead, Lead> implements LeadService {

    /** [db-design §3.4] 的 stage 取值域；越界值直接拒，避免脏枚举污染看板分组。 */
    private static final Set<String> STAGES =
            Set.of("NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST");

    private static final String SIGNED = "SIGNED";

    private static final Logger log = LoggerFactory.getLogger(LeadServiceImpl.class);

    private final SiteAgentService siteAgents;

    public LeadServiceImpl(LocLeadMapper mapper, SiteAgentService siteAgents) {
        super(mapper);
        this.siteAgents = siteAgents;
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
        return new String[]{"stage", "owner", "ownerType", "regionId", "siteNo"};
    }

    @Override
    protected String orderColumn() {
        return "updated_at";
    }

    @Override
    protected void beforeCreate(LocLead e) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage("NEW");
        if (e.getExpectSites() == null) e.setExpectSites(0);
        if (e.getOwnerType() == null || e.getOwnerType().isBlank()) e.setOwnerType(LeadOwnerType.STAFF.name());
        checkStage(e.getStage());
        checkOwnerType(e.getOwnerType());
    }

    @Override
    protected void beforeUpdate(LocLead e, LocLead current) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage(current.getStage());
        if (e.getOwnerType() == null || e.getOwnerType().isBlank()) e.setOwnerType(current.getOwnerType());
        checkStage(e.getStage());
        checkOwnerType(e.getOwnerType());
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

    private static void checkStage(String stage) {
        if (!STAGES.contains(stage)) {
            throw new IllegalArgumentException("商机阶段非法: " + stage);
        }
    }

    private static void checkOwnerType(String ownerType) {
        if (ownerType != null && LeadOwnerType.of(ownerType).isEmpty()) {
            throw new IllegalArgumentException("商机归属方类型非法: " + ownerType);
        }
    }

    @Override
    protected Lead toVO(LocLead e) {
        return new Lead(e.getLeadNo(), e.getVenueName(), e.getContact(), e.getStage(),
                e.getOwner(), e.getOwnerType(), e.getSiteNo(),
                e.getExpectSites(), e.getNextFollowAt(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }
}
