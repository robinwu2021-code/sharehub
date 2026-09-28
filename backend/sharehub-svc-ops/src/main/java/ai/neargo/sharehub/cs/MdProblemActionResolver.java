package ai.neargo.sharehub.cs;

import ai.neargo.sharehub.api.platform.port.ProblemDirectoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 报障分流的真实现：按 {@code md_problem.suggested_action} 决定这条诉求往哪儿走。
 *
 * <p>在此之前装配的是一个占位实现（已删）—— 它<b>一律返回「转人工」</b>，
 * 而那张字典表**从没被读过**：运营在「常见问题」里把某条改成「自助解决」或「转工单」，
 * 不会有任何效果，也没有任何地方报错（2026-09-28 查实）。
 * 分流做成接口的全部理由就是「运营改策略不用发版」，桩把这个理由架空了。
 *
 * <p><b>三条兜底，都指向同一个方向：宁可多耗客服工时，也不要把真实故障静默吞掉。</b>
 * <ul>
 *   <li>字典里查不到这条问题 → {@code TO_CS}（与桩的兜底一致）；</li>
 *   <li>查到了但该条已下架（{@code status != ENABLED}）→ {@code TO_CS}；
 *       下架意味着运营不再想按它答复，但用户的诉求还在；</li>
 *   <li>字段为空或不是已知动作 → {@code TO_CS}，并打一条 WARN 点名是哪条问题 ——
 *       这是运营配错了，需要人去改，而不是让它静默走别的路。</li>
 * </ul>
 *
 * <p>为什么直接读 {@code MdProblemMapper} 而不再加一层端口：{@code svc-ops} 本就依赖
 * {@code svc-platform}（见 pom），而这里只读一个字段、没有跨进程边界；
 * 真要拆分片时，把本类换成读端口的实现即可，{@code cs} 域其余代码不动。
 */
@Primary
@Component
public class MdProblemActionResolver implements ProblemActionResolver {

    private static final Logger log = LoggerFactory.getLogger(MdProblemActionResolver.class);

    private final ProblemDirectoryPort problems;

    public MdProblemActionResolver(ProblemDirectoryPort problems) {
        this.problems = problems;
    }

    @Override
    public String resolve(String problemNo) {
        String action = problems.suggestedActionOf(problemNo);
        if (action == null) {
            return TO_CS;   // 查不到 / 已归档 / 已下架：都没有可用策略
        }
        if (SELF_SERVICE.equals(action) || TO_CS.equals(action)
                || TO_REFUND.equals(action) || TO_WORKORDER.equals(action)) {
            return action;
        }
        log.warn("问题字典的建议处置无法识别，本条按转人工处理 problemNo={} suggestedAction={} —— "
                + "请在「常见问题」里把它改成 SELF_SERVICE / TO_CS / TO_REFUND / TO_WORKORDER 之一", problemNo, action);
        return TO_CS;
    }
}
