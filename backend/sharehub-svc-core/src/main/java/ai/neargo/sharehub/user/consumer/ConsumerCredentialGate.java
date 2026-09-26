package ai.neargo.sharehub.user.consumer;

/**
 * C 端登录口令端口（{@code cred_credential} 的 {@code realm=CONSUMER}）。
 *
 * <p><b>为什么不直接用 {@code platform.cred.CredentialService}</b>：它在
 * {@code sharehub-svc-platform}，本包在 {@code sharehub-svc-core}。两个 svc 模块互相依赖
 * 会让模块拆分静默失效（{@code ArchitectureTest} 的零包循环硬规则），下沉 {@code common}
 * 会撞「零业务依赖」守卫 G4。所以本模块只声明需要什么，装配在 {@code sharehub-app}。
 * 这段理由与 {@link LoginMethodGate}、{@code OtpGate} 完全相同。
 *
 * <p><b>为什么复用员工那张凭据表而不新建一套</b>：锁定阈值、锁定时长、散列算法、
 * 最短长度这些策略各写一份必然分叉，而分叉出来的那一份通常没人测
 * （与 {@code OtpGateConfig} 让 C 端和代理共用 OTP 的理由相同）。
 * 那张表从建起来就是按 realm 参数化的（{@code uk_realm_subject}，表注释「员工与代理共用」）。
 *
 * <p>⚠️ {@code pb_auth} 分库<b>不是本能力的前置</b>：既定取舍是「先在 pb_core 起一张平行
 * 凭据表，auth-core 落地时整表迁移」（见 docs/api/README.md §口令）。多一个 realm 的行，
 * 不是多一张表，所以不增加那次迁移的工作量。
 */
public interface ConsumerCredentialGate {

    /** realm 常量，与 {@code cred_credential.realm} 的取值一致。 */
    String REALM = "CONSUMER";

    /** 校验结果。{@code hasCredential=false} 表示这个人没设过口令。 */
    record Check(boolean ok, boolean hasCredential, String reason) {
    }

    /**
     * 校验口令。锁定期内**口令正确也拒**（策略在 {@code CredentialService}，此处只转发）。
     *
     * <p>调用方必须把「没有凭据」「口令错」「被锁」三种情况**回成同一句文案**：
     * 区分开来就是一台「这个号注册过没有」的探测器。
     */
    Check verify(String cUserNo, String rawPassword);

    /** 用户自设口令（注册时设 / 忘记密码经 OTP 验证后重设）。调用方负责先证明是本人。 */
    void setPassword(String cUserNo, String rawPassword);

    /** 本人改密，校验旧口令。 */
    void changePassword(String cUserNo, String oldPassword, String newPassword);
}
