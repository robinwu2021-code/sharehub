package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentCommission;
import ai.neargo.sharehub.agent.ext.entity.AgtCommission;
import ai.neargo.sharehub.agent.ext.mapper.AgtCommissionMapper;
import ai.neargo.sharehub.agent.ext.service.AgentCommissionService;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 代理分润配置实现。
 *
 * <p>核心校验：**{@code dimension} 与金额字段必须自洽**（[db-design §3.5]）。
 */
@Service
public class AgentCommissionServiceImpl extends AbstractCrudService<AgtCommission, AgentCommission>
        implements AgentCommissionService {

    private static final String DIM_GMV = "GMV";
    private static final String DIM_ORDER_COUNT = "ORDER_COUNT";

    public AgentCommissionServiceImpl(AgtCommissionMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "rule_no";
    }

    @Override
    protected String keyOf(AgtCommission e) {
        return e.getRuleNo();
    }

    @Override
    protected void setKey(AgtCommission e, String no) {
        e.setRuleNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.AGENT_COMMISSION;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"rule_no", "agent_no", "agent_name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"agentNo", "dimension", "mode", "status"};
    }

    @Override
    protected void beforeCreate(AgtCommission e) {
        if (e.getDimension() == null || e.getDimension().isBlank()) e.setDimension(DIM_GMV);
        if (e.getMode() == null || e.getMode().isBlank()) e.setMode("LEDGER");
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED");
        checkDimension(e);
    }

    @Override
    protected void beforeUpdate(AgtCommission e, AgtCommission current) {
        // 归属代理决定这条分润规则**算给谁**，改了等于把钱划到别人账上。
        // 代理归属的变更有专门入口（代理划拨 /assignments），不许经更新接口顺手改。
        // —— 2026-09-23 批量赋值加固（TDD-mass-assignment-hardening）
        e.setAgentNo(current.getAgentNo());
        if (e.getDimension() == null || e.getDimension().isBlank()) e.setDimension(current.getDimension());
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency(current.getCurrency());
        checkDimension(e);
    }

    /**
     * 维度 ↔ 金额字段自洽校验。
     *
     * <p>为什么必须在写入时挡：两个维度共用一张表、各用一列，若放任「GMV 维度但 rate 为空」落库，
     * 结算跑批时才发现，而那时钱已经按 0 分完了。规则配置错了要在保存那一刻就报出来。
     */
    private static void checkDimension(AgtCommission e) {
        String dim = e.getDimension();
        if (DIM_GMV.equals(dim)) {
            if (e.getRate() == null) {
                throw new IllegalArgumentException("dimension=GMV 时 rate 必填（0..1 小数比率）");
            }
            if (e.getRate().compareTo(BigDecimal.ZERO) < 0 || e.getRate().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("rate 取值须在 0..1（[db-design §1.5] 小数比率）: " + e.getRate());
            }
            if (e.getFixedAmount() == null) e.setFixedAmount(BigDecimal.ZERO);   // 非本维度字段归零，不留脏值
        } else if (DIM_ORDER_COUNT.equals(dim)) {
            if (e.getFixedAmount() == null) {
                throw new IllegalArgumentException("dimension=ORDER_COUNT 时 fixedAmount 必填（单均固定额）");
            }
            if (e.getFixedAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("fixedAmount 不可为负: " + e.getFixedAmount());
            }
            if (e.getCurrency() == null || e.getCurrency().isBlank()) {
                throw new IllegalArgumentException("dimension=ORDER_COUNT 时 currency 必填（[db-design §1.5] 金额必带币种）");
            }
            if (e.getRate() == null) e.setRate(BigDecimal.ZERO);
        } else {
            throw new IllegalArgumentException("分润维度非法（仅 GMV / ORDER_COUNT）: " + dim);
        }
    }

    @Override
    protected AgentCommission toVO(AgtCommission e) {
        // 前端字段名是 basis，库列名是 dimension —— 转换只在这一层
        return new AgentCommission(e.getRuleNo(), e.getAgentNo(), e.getAgentName(), e.getDimension(),
                e.getRate(), e.getFixedAmount(), e.getCurrency(), e.getMode(),
                e.getEffectiveAt(), e.getStatus());
    }
}
