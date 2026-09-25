package ai.neargo.sharehub.platform.sys.port;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.platform.sys.entity.SysParam;
import ai.neargo.sharehub.platform.sys.mapper.SysParamMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** {@link SysParamPort} 的本地实现。每次现查 —— 调用都在低频的审批 / 定时任务里，改了参数立即生效比省一次查询要紧。 */
@Component
public class LocalSysParam implements SysParamPort {

    private static final Logger log = LoggerFactory.getLogger(LocalSysParam.class);
    private static final String TENANT = "MAIN";

    private final SysParamMapper params;

    public LocalSysParam(SysParamMapper params) {
        this.params = params;
    }

    @Override
    public int intOf(String key, int defaultValue) {
        String v = raw(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("系统参数不是整数 key={} value={}，按默认值 {} 处理；请在系统参数页修正", key, v, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal decimalOf(String key, BigDecimal defaultValue) {
        String v = raw(key);
        if (v == null) return defaultValue;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.warn("系统参数不是数字 key={} value={}，按默认值 {} 处理；请在系统参数页修正", key, v, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public String textOf(String key, String defaultValue) {
        String v = raw(key);
        return v == null ? defaultValue : v.trim();
    }

    private String raw(String key) {
        SysParam p = params.selectOne(new LambdaQueryWrapper<SysParam>()
                .eq(SysParam::getTenantId, TENANT).eq(SysParam::getParamKey, key).last("limit 1"));
        return p == null || p.getValue() == null || p.getValue().isBlank() ? null : p.getValue();
    }
}
