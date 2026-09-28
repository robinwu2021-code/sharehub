package ai.neargo.sharehub.cs;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ProblemActionResolver} 的**占位实现** —— 骨架期让 cs 域可独立编译与自测。
 *
 * <p><b>这不是最终实现</b>：真正的分流规则存在 {@code md_problem.suggested_action} 列，
 * 该表归 platform/md 子域（另一分片）。等 md 分片就位后，新增一个读该表的实现类并给它标
 * {@code @Primary}，即可顶掉本类，cs 域代码零改动 —— 这就是把分流做成接口的全部理由
 * （[api/README §6A.2]：分流规则不硬编码，运营改策略不用发版）。
 *
 * <p>没有用 {@code @ConditionalOnMissingBean}：该注解只在自动配置类里可靠生效，
 * 放在普通 {@code @Component} 上是静默失效的假保护。真实现忘了标 {@code @Primary} 时，
 * Spring 会以 NoUniqueBeanDefinition 大声失败 —— 这比静默选错实现好。
 *
 * <p>兜底值选 {@link ProblemActionResolver#TO_CS}（转人工）而不是 SELF_SERVICE：
 * 字典查不到时把用户推给人工，最坏是多耗一点客服工时；若兜底成「自助解决」直接关单，
 * 用户的真实故障会被静默吞掉，那是不可接受的失败方向。
 */
@Component
public class StubProblemActionResolver implements ProblemActionResolver {
    private static final Logger log = LoggerFactory.getLogger(StubProblemActionResolver.class);

    /**
     * 照 {@code StubPaymentPort} 的做法在启动日志里喊一声。
     *
     * <p>本桩此前<b>完全静默</b>：它是唯一实现、无条件装配，于是被一路带到生产而没有任何症状 ——
     * 症状全落在用户那一侧（见类注释），而看日志的人一无所知。
     * 模拟成功的东西必须在日志里显形。
     */
    @PostConstruct
    void warn() {
        log.warn("════════════════════════════════════════════════════════════");
        log.warn("  报障分流是**桩实现**（StubProblemActionResolver）：一律「转人工」，");
        log.warn("  md_problem.suggested_action 那张表**从没被读过** —— 运营在字典里改策略不会有任何效果。");
        log.warn("  真实现读该表并标 @Primary，本行随之消失。");
        log.warn("════════════════════════════════════════════════════════════");
    }


    @Override
    public String resolve(String problemNo) {
        return TO_CS;
    }
}
