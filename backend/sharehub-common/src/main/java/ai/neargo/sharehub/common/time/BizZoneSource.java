package ai.neargo.sharehub.common.time;

/**
 * 业务时区的**运行时来源**。
 *
 * <h2>为什么要有这个接口</h2>
 * 业务时区 2026-09-28 起可由管理员在运营台改（见
 * {@code docs/technical/方案-业务时区可配置.md}），值存在 {@code sys_param}。
 * 而读 {@code sys_param} 要经 {@code SysParamPort}，它在 {@code sharehub-api} ——
 * **{@code sharehub-api} 依赖 {@code sharehub-common}，反过来引用就成环**
 * （{@code ArchitectureTest#域间无包循环} 是硬规则）。
 *
 * <p>所以在 common 这一侧只声明「我需要一个时区」，由上层
 * （{@code sharehub-svc-platform}，它既依赖 api 又能读参数表）实现。
 * 方向与既有的 {@code *Port} 一致：**接口在下、实现在上**。
 *
 * <h2>缺席时会怎样</h2>
 * {@link BizClock} 用 {@code ObjectProvider} 取它，取不到就回落
 * {@code sharehub.biz-zone} 这个启动期属性。所以：
 * <ul>
 *   <li>只装配了 common 的单元测试不需要它；</li>
 *   <li>参数表里没配、或实现方暂时缺席，行为与改造之前**完全一致**。</li>
 * </ul>
 */
public interface BizZoneSource {

    /**
     * 当前业务时区的 IANA ID（如 {@code Asia/Dubai}）。
     *
     * @return 未配置时返回 {@code null} 或空串 —— 由调用方回落启动期默认值。
     *         <b>实现方不要在这里吞异常回落</b>：值非法应当在**写入时**就被拒，
     *         在读取处兜底会让界面显示「已保存」而实际没生效。
     */
    String zoneId();
}
