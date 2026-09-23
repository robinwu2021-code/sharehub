package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.loc.ext.SiteAgentRole;
import ai.neargo.sharehub.loc.ext.dto.SiteAgentDtos.SiteAgentRow;
import ai.neargo.sharehub.loc.ext.entity.LocSiteAgent;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteAgentMapper;
import ai.neargo.sharehub.loc.ext.service.SiteAgentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 站点伙伴责任实现。校验都在这里 —— 库的 UK 只拦「同人同站同责任」，拦不住业务层的互斥。 */
@Service
public class SiteAgentServiceImpl implements SiteAgentService {

    private final LocSiteAgentMapper mapper;
    /** 伙伴名录走契约而不是 agent 的 mapper —— 见 AgentDirectoryPort 类注释里的「为什么」。 */
    private final AgentDirectoryPort agents;

    public SiteAgentServiceImpl(LocSiteAgentMapper mapper, AgentDirectoryPort agents) {
        this.mapper = mapper;
        this.agents = agents;
    }

    @Override
    public List<SiteAgentRow> ofSite(String siteNo) {
        if (siteNo == null || siteNo.isBlank()) return List.of();
        List<LocSiteAgent> rows = mapper.selectList(new LambdaQueryWrapper<LocSiteAgent>()
                .eq(LocSiteAgent::getSiteNo, siteNo));
        if (rows.isEmpty()) return List.of();

        // 一次取回伙伴名录再拼名字，避免逐行回表（N+1）
        Map<String, AgentBrief> byNo = agents.briefsOf(
                rows.stream().map(LocSiteAgent::getAgentNo).distinct().toList());

        return rows.stream()
                // 按责任枚举的声明顺序排，而不是按 id —— 同一个站点两次打开顺序要一致，
                // 否则「上次那行在哪」要重新找
                .sorted(Comparator.comparingInt(r -> SiteAgentRole.of(r.getRole())
                        .map(Enum::ordinal).orElse(Integer.MAX_VALUE)))
                .map(r -> {
                    AgentBrief a = byNo.get(r.getAgentNo());
                    return new SiteAgentRow(r.getId(), r.getSiteNo(), r.getAgentNo(),
                            a == null ? null : a.name(),
                            a == null || a.agentType() == null ? null : a.agentType().name(),
                            r.getRole(), r.getRuleNo(), str(r.getEffectiveFrom()), str(r.getEffectiveTo()),
                            r.getRemark());
                })
                .toList();
    }

    @Override
    @Transactional
    public SiteAgentRow upsert(String siteNo, SiteAgentRow in) {
        if (siteNo == null || siteNo.isBlank()) throw new IllegalArgumentException("责任必须挂在一个站点上");
        if (in.agentNo() == null || in.agentNo().isBlank()) throw new IllegalArgumentException("请选择合作伙伴");
        SiteAgentRole role = SiteAgentRole.of(in.role())
                .orElseThrow(() -> new IllegalArgumentException("未知的责任：" + in.role()));

        LocalDateTime from = time(in.effectiveFrom()), to = time(in.effectiveTo());
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("生效止不能早于生效起");
        }

        AgentBrief agent = agents.briefOf(in.agentNo());
        // 悬空的 agent_no 会让分润按一个不存在的受益方生成记录，而且不报错
        if (agent == null) throw new IllegalArgumentException("合作伙伴不存在: " + in.agentNo());

        List<LocSiteAgent> exist = mapper.selectList(new LambdaQueryWrapper<LocSiteAgent>()
                .eq(LocSiteAgent::getSiteNo, siteNo)
                .eq(LocSiteAgent::getAgentNo, in.agentNo()));
        for (LocSiteAgent e : exist) {
            if (in.id() != null && in.id().equals(e.getId())) continue;
            SiteAgentRole other = SiteAgentRole.of(e.getRole()).orElse(null);
            if (other == null) continue;
            if (other == role) {
                throw new IllegalArgumentException(
                        agent.name() + " 在本站点已经有「" + label(role) + "」这条责任了，直接改那一行即可。");
            }
            if (role.conflictsWith(other)) {
                // 牵线是拓展的弱形式，同一人同一站点只能算其一 —— 否则同一件事付两份钱
                throw new IllegalArgumentException(
                        "「" + label(role) + "」与「" + label(other) + "」不能并存："
                                + "牵线是拓展的弱形式，同一个人在同一个站点只能算其一。");
            }
        }

        LocSiteAgent e = in.id() == null ? new LocSiteAgent() : mapper.selectById(in.id());
        if (e == null) throw new IllegalArgumentException("责任行不存在: " + in.id());
        e.setSiteNo(siteNo);
        e.setAgentNo(in.agentNo());
        e.setRole(role.name());
        e.setRuleNo(blankToNull(in.ruleNo()));
        e.setEffectiveFrom(from);
        e.setEffectiveTo(to);
        e.setRemark(in.remark() == null ? "" : in.remark().trim());
        if (e.getId() == null) mapper.insert(e); else mapper.updateById(e);

        return new SiteAgentRow(e.getId(), e.getSiteNo(), e.getAgentNo(), agent.name(),
                agent.agentType() == null ? null : agent.agentType().name(), e.getRole(), e.getRuleNo(),
                str(e.getEffectiveFrom()), str(e.getEffectiveTo()), e.getRemark());
    }

    @Override
    @Transactional
    public void remove(String siteNo, Long id) {
        if (id == null) return;
        LocSiteAgent e = mapper.selectById(id);
        // 路径上的 siteNo 为准：不校验的话，拿到任意 id 就能撤销别的站点的责任
        if (e == null || !e.getSiteNo().equals(siteNo)) {
            throw new IllegalArgumentException("责任行不存在或不属于该站点: " + id);
        }
        mapper.deleteById(id);
    }

    private static String label(SiteAgentRole r) {
        return switch (r) {
            case INVEST -> "出资";
            case DEVELOP -> "拓展";
            case OPERATE -> "运维";
            case REFER -> "牵线";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    /** 界面可能给 `2026-09-23`（date 控件）或 `2026-09-23T10:00`（datetime-local）。 */
    private static LocalDateTime time(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String v = iso.trim();
        return v.length() == 10 ? LocalDate.parse(v).atStartOfDay() : LocalDateTime.parse(v);
    }
}
