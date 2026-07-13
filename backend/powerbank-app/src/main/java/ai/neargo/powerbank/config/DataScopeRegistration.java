package ai.neargo.powerbank.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * powerbank 数据范围表注册（neargo {@link DataScopeRegistrar}）：声明各表在各维度用哪列过滤。
 * 未注册的表=全局放行。MVP 仅 {@code loc_site}（已落库带锚点列）；dev/ord/wo 待补 agent_no 列后加入。
 */
@Component
public class DataScopeRegistration implements DataScopeRegistrar {

    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("loc_site", Map.of(
                "AGENT", "agent_no",
                "REGION", "region_id",
                "SITE", "site_no"));
        // 待接入（补 agent_no/site_no 列后）：dev_cabinet / ord_rent / wo_order
    }
}
