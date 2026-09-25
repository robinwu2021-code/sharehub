package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment;
import ai.neargo.sharehub.agent.ext.mapper.AgtOpsAssessmentMapper;
import ai.neargo.sharehub.agent.ext.service.AgentOpsAssessmentService;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/** 月度考核实现。跨工单 / 设备 / 客服表的计数用 SQL 直读（同 {@code AgentExitServiceImpl} 的理由：避免包依赖成环）。 */
@Service
public class AgentOpsAssessmentServiceImpl implements AgentOpsAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentOpsAssessmentServiceImpl.class);

    private final AgentMapper agents;
    private final AgtOpsAssessmentMapper assessments;
    private final JdbcTemplate jdbc;
    private final SysParamPort params;

    public AgentOpsAssessmentServiceImpl(AgentMapper agents, AgtOpsAssessmentMapper assessments, JdbcTemplate jdbc, SysParamPort params) {
        this.agents = agents;
        this.assessments = assessments;
        this.jdbc = jdbc;
        this.params = params;
    }

    @Override
    @Transactional
    public int assess(String period) {
        YearMonth ym = YearMonth.parse(period);
        LocalDateTime from = ym.atDay(1).atStartOfDay(), to = ym.plusMonths(1).atDay(1).atStartOfDay();
        int n = 0;
        for (AgtAgent a : agents.selectList(new LambdaQueryWrapper<AgtAgent>().isNull(AgtAgent::getArchivedAt))) {
            String ag = a.getAgentNo();
            int done = count("SELECT COUNT(*) FROM wo_order WHERE assignee_type = 'AGENT' AND assignee_name = ?"
                    + " AND status IN ('DONE','AUDITED','CLOSED') AND created_at >= ? AND created_at < ?", ag, from, to);
            int inSla = count("SELECT COUNT(*) FROM wo_order o JOIN wo_sla s ON s.wo_no = o.wo_no WHERE o.assignee_type = 'AGENT'"
                    + " AND o.assignee_name = ? AND o.status IN ('DONE','AUDITED','CLOSED') AND s.resolve_breached = 0"
                    + " AND o.created_at >= ? AND o.created_at < ?", ag, from, to);
            // 没有 SLA 计时行的完结单（没配规则）视为达成：没有时限就谈不上超时
            int noSla = count("SELECT COUNT(*) FROM wo_order o WHERE o.assignee_type = 'AGENT' AND o.assignee_name = ?"
                    + " AND o.status IN ('DONE','AUDITED','CLOSED') AND o.created_at >= ? AND o.created_at < ?"
                    + " AND NOT EXISTS (SELECT 1 FROM wo_sla s WHERE s.wo_no = o.wo_no)", ag, from, to);
            int takenOver = count("SELECT COUNT(*) FROM wo_order WHERE taken_over_from = ? AND taken_over_at >= ? AND taken_over_at < ?", ag, from, to);
            int total = done + takenOver;
            BigDecimal sla = total == 0 ? null : BigDecimal.valueOf(inSla + noSla).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            int cabs = count("SELECT COUNT(*) FROM dev_cabinet WHERE deleted = 0 AND archived_at IS NULL AND agent_no = ? AND status = 'DEPLOYED'", ag);
            int online = count("SELECT COUNT(*) FROM dev_cabinet WHERE deleted = 0 AND archived_at IS NULL AND agent_no = ? AND status = 'DEPLOYED'"
                    + " AND online_status = 'ONLINE'", ag);
            BigDecimal onlineRate = cabs == 0 ? null : BigDecimal.valueOf(online).divide(BigDecimal.valueOf(cabs), 4, RoundingMode.HALF_UP);
            int complaints = count("SELECT COUNT(*) FROM cs_ticket t JOIN dev_cabinet c ON c.cabinet_no = t.cabinet_no WHERE t.deleted = 0"
                    + " AND c.agent_no = ? AND t.created_at >= ? AND t.created_at < ?", ag, from, to);

            AgtOpsAssessment r = assessments.selectOne(new LambdaQueryWrapper<AgtOpsAssessment>()
                    .eq(AgtOpsAssessment::getAgentNo, ag).eq(AgtOpsAssessment::getPeriod, period).last("limit 1"));
            boolean insert = r == null;
            if (insert) {
                r = new AgtOpsAssessment();
                r.setAgentNo(ag);
                r.setPeriod(period);
            }
            r.setApplyPeriod(ym.plusMonths(1).toString());
            r.setWoTotal(total);
            r.setWoInSla(inSla + noSla);
            r.setSlaRate(sla);
            r.setOnlineRate(onlineRate);
            r.setComplaints(complaints);
            r.setTakenOver(takenOver);
            r.setCoefficient(coefficientOf(sla));
            r.setComputedAt(LocalDateTime.now());
            if (insert) assessments.insert(r); else assessments.updateById(r);
            n++;
        }
        log.info("代理运维考核 period={} agents={}", period, n);
        return n;
    }

    /** 分档：≥ 满档线不打折；≥ 中档线按中档系数；以下按低档系数。没有工单（无从考核）不打折。 */
    BigDecimal coefficientOf(BigDecimal sla) {
        if (sla == null) return BigDecimal.ONE;
        BigDecimal full = params.decimalOf("agent.ops.coef.full_rate", new BigDecimal("0.95"));
        BigDecimal midRate = params.decimalOf("agent.ops.coef.mid_rate", new BigDecimal("0.80"));
        if (sla.compareTo(full) >= 0) return BigDecimal.ONE;
        if (sla.compareTo(midRate) >= 0) return params.decimalOf("agent.ops.coef.mid", new BigDecimal("0.90"));
        return params.decimalOf("agent.ops.coef.low", new BigDecimal("0.80"));
    }

    @Override
    public List<AgtOpsAssessment> history(String agentNo) {
        return assessments.selectList(new LambdaQueryWrapper<AgtOpsAssessment>().eq(AgtOpsAssessment::getAgentNo, agentNo)
                .orderByDesc(AgtOpsAssessment::getPeriod));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
