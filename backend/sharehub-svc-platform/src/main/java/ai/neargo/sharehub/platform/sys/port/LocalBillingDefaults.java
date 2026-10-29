package ai.neargo.sharehub.platform.sys.port;

import ai.neargo.sharehub.api.platform.port.BillingDefaultsPort;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import org.springframework.stereotype.Component;

/** {@link BillingDefaultsPort} 的本地实现：读系统设置的计费默认（{@code sys_biz_rule}）。 */
@Component
public class LocalBillingDefaults implements BillingDefaultsPort {

    private final BizRuleService rules;

    public LocalBillingDefaults(BizRuleService rules) {
        this.rules = rules;
    }

    @Override
    public Integer overdueHours() {
        var all = rules.get();
        return all == null || all.billing() == null ? null : all.billing().overdueHours();
    }
}
