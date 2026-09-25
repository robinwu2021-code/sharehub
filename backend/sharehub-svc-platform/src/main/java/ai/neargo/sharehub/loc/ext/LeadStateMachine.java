package ai.neargo.sharehub.loc.ext;

import ai.neargo.sharehub.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 商机阶段迁移（对齐清单 B5）：NEW → CONTACTED → NEGOTIATING → SIGNED 顺序推进；任一在跟阶段可 LOST（原因必填，
 * 由 service 校验）；LOST 可重新激活回 NEW（人工，或竞品独家到期前由定时任务）。
 *
 * <p>允许一步回退 NEGOTIATING → CONTACTED：谈崩了退回重新接触是 BD 的日常，不该逼人先 LOST 再激活。
 * SIGNED 是终态：签下后的变化（补充协议、续签）在合同上发生，不回到商机。
 *
 * <p>新建商机不是迁移：导入 / 补录已签的历史商机时直接落在对应阶段（LeadAttributionTest 依赖这一点）。
 */
@Component
public class LeadStateMachine {

    private static final Map<String, Map<LeadStatus, LeadStatus>> TRANSITIONS = Map.of(
            "CONTACT", Map.of(LeadStatus.NEW, LeadStatus.CONTACTED),
            "NEGOTIATE", Map.of(LeadStatus.CONTACTED, LeadStatus.NEGOTIATING),
            "STEP_BACK", Map.of(LeadStatus.NEGOTIATING, LeadStatus.CONTACTED),
            "SIGN", Map.of(LeadStatus.NEGOTIATING, LeadStatus.SIGNED),
            "LOSE", Map.of(LeadStatus.NEW, LeadStatus.LOST, LeadStatus.CONTACTED, LeadStatus.LOST,
                    LeadStatus.NEGOTIATING, LeadStatus.LOST),
            "REACTIVATE", Map.of(LeadStatus.LOST, LeadStatus.NEW));

    /** 校验 from → to 是一条合法迁移（同阶段视为未推进，放行），返回事件名；非法抛 400。 */
    public String check(String from, String to) {
        LeadStatus f = LeadStatus.of(from).orElseThrow(() -> BizException.badRequest("error.lead.stage_invalid", from));
        LeadStatus t = LeadStatus.of(to).orElseThrow(() -> BizException.badRequest("error.lead.stage_invalid", to));
        if (f == t) return null;
        return TRANSITIONS.entrySet().stream().filter(e -> e.getValue().get(f) == t).map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> BizException.badRequest("error.lead.stage_illegal", from, to));
    }
}
