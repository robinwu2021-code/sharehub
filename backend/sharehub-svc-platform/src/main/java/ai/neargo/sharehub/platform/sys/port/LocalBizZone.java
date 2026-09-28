package ai.neargo.sharehub.platform.sys.port;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.common.time.BizZoneSource;
import org.springframework.stereotype.Component;

/**
 * 业务时区的运行时来源：读系统参数 {@code biz.timezone}。
 *
 * <h2>为什么实现放这里而不是 common</h2>
 * {@link BizZoneSource} 声明在 {@code sharehub-common}（{@code BizClock} 在那儿），
 * 而读参数要经 {@link SysParamPort}，它在 {@code sharehub-api} ——
 * **api 依赖 common，反过来引用就成环**。所以接口在下、实现在上，
 * 与既有的 {@code *Port} / {@code Local*} 同一方向。
 *
 * <h2>没配时返回 null，不在这里兜底</h2>
 * 兜底值是 {@code sharehub.biz-zone} 这个启动期属性，由 {@code BizClock} 持有 ——
 * 兜底逻辑只该有一处。这里返回 null 表示「参数表里没有」，仅此而已。
 *
 * <p>同理**不校验**取回的值：值非法应当在写入时就被拒
 * （{@code SysConfigController} 的校验），在读取处静默纠正会让界面显示
 * 「已保存」而实际没生效 —— 那比直接报错难查得多。
 */
@Component
public class LocalBizZone implements BizZoneSource {

    /** 系统参数键。与 {@code SysConfigController} 的写入校验、运营台的时区设置项同一个常量。 */
    public static final String KEY = "biz.timezone";

    private final SysParamPort params;

    public LocalBizZone(SysParamPort params) {
        this.params = params;
    }

    @Override
    public String zoneId() {
        // textOf 的约定：空串视为未配置，返回默认值 —— 这里传 null 当默认，即「没配」
        return params.textOf(KEY, null);
    }
}
