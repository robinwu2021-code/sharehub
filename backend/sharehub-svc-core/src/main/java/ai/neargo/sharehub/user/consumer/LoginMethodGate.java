package ai.neargo.sharehub.user.consumer;

/**
 * 登录方式开关端口（{@code sys_login_setting}，需求 OPS-SYS-07）。
 *
 * <p><b>为什么要这个接口，而不是直接用 {@code platform.sys.LoginSettingService}</b>：
 * 那个类在 {@code sharehub-svc-platform}，而本包在 {@code sharehub-svc-core}。
 * 两个 svc 模块互相依赖会让模块拆分静默失效（{@code ArchitectureTest} 的零包循环硬规则），
 * 下沉到 {@code common} 也不行 —— 那里有「零业务依赖」守卫（G4）。
 * 所以本模块只声明「我需要知道某种登录方式开不开」，实现由 {@code sharehub-app} 装配。
 * <b>这段理由与 {@code OtpGate} 一字不差</b>，那是同一个约束下的同一个解法。
 *
 * <p>没装配时应用<b>起不来</b>（缺 bean），这是有意的：开关没接上时的默认行为要么
 * 放行一切（等于没开关）、要么拒绝一切（把人全锁在门外），两种都比起不来糟。
 */
public interface LoginMethodGate {

    /**
     * 该登录方式当前是否开启。
     *
     * @param country ISO alpha-2（{@code AE}/{@code SA}…）；空则按默认行 {@code '*'}。
     *                <p>⚠️ <b>这个值由端上声明，不可信</b>。所以它能做到的是
     *                「全局关掉的方式一定用不了」，做不到「A 国的人一定用不了 B 国的方式」——
     *                后者要靠**手机号区号反查 ISO**，而今天没有任何一张表存区号
     *                （{@code md_market_country} 只有 ISO，没有 calling code 列）。
     *                这个边界写在这里，免得下一个人以为按国家收紧是可信的。
     * @param grantType 与 {@link ConsumerLoginStrategy#grantType()} 同一个词表
     * @return 开则 true；查不到配置（连默认行都没有）时抛，不静默放行
     */
    boolean enabled(String country, String grantType);
}
