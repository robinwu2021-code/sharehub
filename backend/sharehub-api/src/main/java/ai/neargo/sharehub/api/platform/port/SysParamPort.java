package ai.neargo.sharehub.api.platform.port;

import java.math.BigDecimal;

/**
 * 系统参数读取（platform.sys 实现）。运营可调的阈值 / 天数走 sys_param，不写死在代码里。
 *
 * <p><b>调用方必须给默认值</b>：参数行缺失、值写坏了都回落到默认值并记 WARN ——
 * 一个参数写错不该让合同审批、商机回收整条链路报错。默认值应与迁移里的种子一致。
 */
public interface SysParamPort {

    int intOf(String key, int defaultValue);

    BigDecimal decimalOf(String key, BigDecimal defaultValue);

    /** 文本参数；空串视为未配置。 */
    String textOf(String key, String defaultValue);
}
