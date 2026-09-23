package ai.neargo.sharehub.finance.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.event.ContractSignedEvent;
import ai.neargo.sharehub.finance.service.ReferFeeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * finance 侧订阅「合同已签」，结一次性牵线费（ADR-027 §四）。
 *
 * <p>三条与 {@link OrderSettledListener} 同源的理由，不再展开：
 * {@code REQUIRES_NEW}（监听器在发布方事务提交后执行，此时已无活动事务）·
 * 豁免数据范围（系统按事件写账，没有「操作者」；被 scope 过滤会让一笔钱凭空消失且不报错）·
 * 不吞异常（失败让 outbox 重投；吞掉的话这笔牵线费永远不会再有人去补）。
 */
@Component
public class ContractSignedListener {

    private static final Logger log = LoggerFactory.getLogger(ContractSignedListener.class);

    private final ReferFeeGenerator generator;

    public ContractSignedListener(ReferFeeGenerator generator) {
        this.generator = generator;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ContractSignedEvent e) {
        int n = DataScopeContext.executeWithoutScope(() -> generator.generate(e));
        if (n > 0) {
            log.info("合同 {} 签约 → 结出牵线费 {} 笔（站点 {}）", e.contractNo(), n, e.siteNo());
        }
    }
}
