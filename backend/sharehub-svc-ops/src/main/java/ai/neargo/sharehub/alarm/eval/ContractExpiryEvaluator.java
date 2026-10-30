package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.platform.dto.ContractBrief;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import ai.neargo.sharehub.wo.WoPriority;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合同即将到期（对齐清单 B1 · E3）：生效中合同 60 天内到期即成立，30 / 7 天两次升级。
 *
 * <p><b>「每档提醒一次」靠引擎的影响升级实现</b>：同一份合同只开一条告警（去重键按合同号），
 * 开时推一次（OPENED），跨进 30 天、7 天各升一档（IMPACT_UP，只升不降）各推一次 —— 不需要自己记「提醒过没有」。
 * 待办按码的归属角色（BD）只开一张；推送受众在通知规则里配（默认 BD、运营）。
 *
 * <p>恢复：续签合同已提交（待审批 / 已批）、终止申请在途或已批、站点进入撤场 —— 这些合同由端口排除，
 * 不再成立即走正常的恢复关闭。到期当天之后合同变 EXPIRED，同样不再成立；接下来由「无合同在营业」接手。
 */
@Component
public class ContractExpiryEvaluator implements StateEvaluator {

    public static final String CONTRACT_EXPIRING = "CONTRACT_EXPIRING";
    /**
     * 提醒窗口与两档升级都是配置（C12，V184；运营方原文「到期前 2 个月提醒」—— 60 / 61 / 62 天由运营定）：
     * contract.expiry.remind_days（60）、contract.expiry.escalate_medium_days（30）、contract.expiry.escalate_high_days（7）。
     */
    static final int WINDOW_DAYS = 60;
    static final String KEY_WINDOW = "contract.expiry.remind_days";
    static final String KEY_MEDIUM = "contract.expiry.escalate_medium_days";
    static final String KEY_HIGH = "contract.expiry.escalate_high_days";

    private final ContractQueryPort contracts;
    private final ai.neargo.sharehub.api.platform.port.SysParamPort params;

    public ContractExpiryEvaluator(ContractQueryPort contracts, ai.neargo.sharehub.api.platform.port.SysParamPort params) {
        this.contracts = contracts;
        this.params = params;
    }

    @Override
    public Set<String> codes() {
        return Set.of(CONTRACT_EXPIRING);
    }

    /** 按合同全局判定：合同数量级小，一轮一次查完；不跟站点批次走。 */
    @Override
    public boolean siteBatched() {
        return false;
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        int window = Math.max(1, params.intOf(KEY_WINDOW, WINDOW_DAYS));
        int medium = params.intOf(KEY_MEDIUM, 30);
        int high = params.intOf(KEY_HIGH, 7);
        return contracts.expiringWithin(window, 1000).stream()
                .filter(c -> c.endAt() != null)
                .map(c -> finding(c, now, medium, high))
                .toList();
    }

    private static Finding finding(ContractBrief c, LocalDateTime now, int medium, int high) {
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), c.endAt());
        return new Finding(CONTRACT_EXPIRING, AlarmSubjectType.CONTRACT, c.contractNo(), c.siteNo(), null, null, null,
                AlarmCause.EXPIRING, ImpactScope.SITE, 0,
                List.of(new Finding.Evidence("CONTRACT_END", null, null, now, "剩余 " + days + " 天（" + c.endAt() + " 到期）")),
                Map.of(Finding.ATTR_PRIORITY, priorityOf(days, medium, high).name(), "endAt", c.endAt().toString()));
    }

    static WoPriority priorityOf(long daysLeft) {
        return priorityOf(daysLeft, 30, 7);
    }

    static WoPriority priorityOf(long daysLeft, int medium, int high) {
        if (daysLeft <= high) return WoPriority.HIGH;
        if (daysLeft <= medium) return WoPriority.MEDIUM;
        return WoPriority.LOW;
    }
}
