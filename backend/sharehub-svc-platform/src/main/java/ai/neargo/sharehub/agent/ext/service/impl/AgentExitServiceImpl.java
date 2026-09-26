package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.agent.AgentStatus;
import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.ext.AccountStatus;
import ai.neargo.sharehub.agent.ext.AgentExitStateMachine;
import ai.neargo.sharehub.agent.ext.AgentExitStatus;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtExit;
import ai.neargo.sharehub.agent.ext.mapper.AgtAccountMapper;
import ai.neargo.sharehub.agent.ext.mapper.AgtExitMapper;
import ai.neargo.sharehub.agent.ext.service.AgentExitService;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.api.platform.event.AgentStatusChangedEvent;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.event.DomainEventBus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 代理清退实现（F3）。门禁的计数跨了场地 / 设备 / 工单 / 财务几张表：<b>直接用 SQL 读</b> ——
 * agent 包若为了几个 COUNT 依赖这四个域，包依赖就成环了（与 dev 读 ord_order 同一个做法）。
 */
@Service
public class AgentExitServiceImpl implements AgentExitService {

    private static final Logger log = LoggerFactory.getLogger(AgentExitServiceImpl.class);
    private static final List<String> OPEN = List.of(AgentExitStatus.RECLAIMING.name(), AgentExitStatus.SETTLING.name(),
            AgentExitStatus.CLOSING.name());

    private final AgtExitMapper exits;
    private final AgentMapper agents;
    private final AgtAccountMapper accounts;
    private final AgentExitStateMachine sm;
    private final JdbcTemplate jdbc;
    private final DomainEventBus events;

    public AgentExitServiceImpl(AgtExitMapper exits, AgentMapper agents, AgtAccountMapper accounts, AgentExitStateMachine sm,
                                JdbcTemplate jdbc, DomainEventBus events) {
        this.exits = exits;
        this.agents = agents;
        this.accounts = accounts;
        this.sm = sm;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public AgentExit openOf(String agentNo) {
        if (agentNo == null || agentNo.isBlank()) return null;
        AgtExit e = exits.selectOne(new LambdaQueryWrapper<AgtExit>()
                .eq(AgtExit::getAgentNo, agentNo).in(AgtExit::getStatus, OPEN)
                .orderByDesc(AgtExit::getId).last("limit 1"));
        return e == null ? null : vo(e);
    }

    @Override
    public AgentExit start(String agentNo, String reason) {
        if (reason == null || reason.isBlank()) throw BizException.badRequest("error.common.reason_required");
        AgtAgent a = agents.selectOne(new LambdaQueryWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        if (a == null) throw BizException.notFound(agentNo);
        Long open = exits.selectCount(new LambdaQueryWrapper<AgtExit>().eq(AgtExit::getAgentNo, agentNo).in(AgtExit::getStatus, OPEN));
        if (open != null && open > 0) throw BizException.conflict("error.agent_exit.in_progress", agentNo);
        String me = operator();
        AgtExit e = new AgtExit();
        e.setExitNo(IdGenerator.next("AX"));
        e.setTenantId("MAIN");
        e.setAgentNo(agentNo);
        e.setStatus(AgentExitStatus.RECLAIMING.name());
        e.setReason(reason.trim());
        e.setStartedBy(me);
        e.setStartedAt(LocalDateTime.now());
        exits.insert(e);
        // 发起即停用：不再派新单、不能新划拨（既有）、提现冻结（F1）、名下未完结工单改派平台（F2，事件驱动）
        if (!AgentStatus.SUSPENDED.name().equals(a.getStatus())) {
            agents.update(null, new LambdaUpdateWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, agentNo)
                    .set(AgtAgent::getStatus, AgentStatus.SUSPENDED.name()));
            events.publish(new AgentStatusChangedEvent(agentNo, a.getStatus(), AgentStatus.SUSPENDED.name(), "清退：" + reason.trim(),
                    LocalDateTime.now().toString()));
        }
        log.info("代理清退发起 exitNo={} agentNo={} by={}", e.getExitNo(), agentNo, me);
        return vo(e);
    }

    @Override
    public AgentExit get(String exitNo) {
        return vo(require(exitNo));
    }

    @Override
    public Checklist gate(String exitNo) {
        return gate(require(exitNo));
    }

    private Checklist gate(AgtExit e) {
        String ag = e.getAgentNo();
        return switch (AgentExitStatus.valueOf(e.getStatus())) {
            case RECLAIMING -> {
                long sites = count("SELECT COUNT(*) FROM loc_site WHERE deleted = 0 AND agent_no = ? AND status <> 'CLOSED'", ag);
                long roles = count("SELECT COUNT(*) FROM loc_site_agent WHERE deleted = 0 AND agent_no = ?"
                        + " AND (effective_to IS NULL OR effective_to >= NOW(3))", ag);
                long cabs = count("SELECT COUNT(*) FROM dev_cabinet WHERE deleted = 0 AND archived_at IS NULL AND agent_no = ? AND status <> 'RETIRED'", ag);
                long wos = count("SELECT COUNT(*) FROM wo_order WHERE assignee_type = 'AGENT' AND assignee_name = ?"
                        + " AND status IN ('CREATED','DISPATCHED','ACCEPTED','PROCESSING')", ag);
                yield Checklist.of(List.of(
                        item("SITES", "站点已收回", sites, "个站点仍归属该代理", "/agents/" + ag + "?tab=assign"),
                        item("SITE_ROLES", "站点责任已解除", roles, "条站点责任（运维 / 拓展）仍生效", "/agents/" + ag + "?tab=sites"),
                        item("CABINETS", "设备已收回", cabs, "台机柜仍归属该代理", "/agents/" + ag + "?tab=assign"),
                        item("WORK_ORDERS", "工单已转出", wos, "张未完结工单仍在它手上", "/work-orders?assignee=" + ag)));
            }
            case SETTLING -> {
                long shares = count("SELECT COUNT(*) FROM share_record WHERE deleted = 0 AND payee_type = 'AGENT' AND payee_no = ? AND status = 'PENDING'", ag);
                long settles = count("SELECT COUNT(*) FROM stl_settlement WHERE deleted = 0 AND payee_no = ? AND status <> 'PAID'", ag);
                long wds = count("SELECT COUNT(*) FROM stl_withdrawal WHERE payee_no = ? AND status IN ('APPLY','AUDIT','PAYING')", ag);
                yield Checklist.of(List.of(
                        item("SHARES", "分润已出账", shares, "条分润还没进结算单", "/finance/shares?payeeNo=" + ag),
                        item("SETTLEMENTS", "结算单已付清", settles, "张结算单未付清", "/finance/settlements?payeeNo=" + ag),
                        item("WITHDRAWALS", "提现已办结", wds, "笔提现在途", "/finance/withdrawals?payeeNo=" + ag)));
            }
            case CLOSING -> Checklist.of(List.of(new Checklist.Item("READY", "可以关闭", true, "关闭将停用全部登录账号并归档代理", null)));
            case CLOSED -> Checklist.of(List.of(new Checklist.Item("CLOSED", "已清退", true, "清退完成于 " + e.getClosedAt(), null)));
        };
    }

    @Override
    @Transactional
    public AgentExit advance(String exitNo) {
        AgtExit e = require(exitNo);
        String event = sm.eventOf(e.getStatus());
        Checklist g = gate(e);
        if (!g.allPassed()) throw BizException.conflict("error.agent_exit.gate_blocked", g.firstFailedKey());
        String to = sm.next(e.getStatus(), event);
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AgtExit> u = new LambdaUpdateWrapper<AgtExit>().eq(AgtExit::getExitNo, exitNo)
                .eq(AgtExit::getStatus, e.getStatus()).set(AgtExit::getStatus, to);
        switch (event) {
            case "RECLAIMED" -> u.set(AgtExit::getReclaimedAt, now);
            case "SETTLED" -> u.set(AgtExit::getSettledAt, now);
            default -> u.set(AgtExit::getClosedAt, now).set(AgtExit::getClosedBy, operator());
        }
        if (exits.update(null, u) == 0) throw BizException.conflict("error.common.state_changed");
        if ("CLOSE".equals(event)) {
            int n = accounts.update(null, new LambdaUpdateWrapper<AgtAccount>().eq(AgtAccount::getAgentNo, e.getAgentNo())
                    .set(AgtAccount::getStatus, AccountStatus.DISABLED.name()));
            agents.update(null, new LambdaUpdateWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, e.getAgentNo())
                    .set(AgtAgent::getStatus, AgentStatus.SUSPENDED.name()).set(AgtAgent::getArchivedAt, now));
            log.info("代理清退完成 exitNo={} agentNo={} 停用账号 {} 个", exitNo, e.getAgentNo(), n);
        }
        return vo(require(exitNo));
    }

    private long count(String sql, String agentNo) {
        Long n = jdbc.queryForObject(sql, Long.class, agentNo);
        return n == null ? 0 : n;
    }

    private static Checklist.Item item(String key, String label, long n, String what, String href) {
        return new Checklist.Item(key, label, n == 0, n == 0 ? "已完成" : "还有 " + n + " " + what, href);
    }

    private AgtExit require(String exitNo) {
        AgtExit e = exits.selectOne(new LambdaQueryWrapper<AgtExit>().eq(AgtExit::getExitNo, exitNo).last("limit 1"));
        if (e == null) throw BizException.notFound(exitNo);
        return e;
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }

    private static AgentExit vo(AgtExit e) {
        return new AgentExit(e.getExitNo(), e.getAgentNo(), e.getStatus(), e.getReason(), e.getStartedBy(), e.getStartedAt(),
                e.getReclaimedAt(), e.getSettledAt(), e.getClosedAt(), e.getClosedBy());
    }
}
