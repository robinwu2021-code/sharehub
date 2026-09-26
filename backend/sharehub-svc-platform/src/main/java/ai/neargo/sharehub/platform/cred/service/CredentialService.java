package ai.neargo.sharehub.platform.cred.service;

/**
 * 登录凭据（P3b · B2）：建号 / 改密 / 校验。
 *
 * <p>B1 只铺了地基（表 + {@code PasswordEncoder}），**没有任何调用方**；
 * 登录当时仍然只认配置里那一个共享口令，于是「双人复核」这类要两个账号的流程
 * 在生产一条都走不通（2026-09-26 业务流程验证实测）。本接口是它的第一个调用方。
 */
public interface CredentialService {

    /** 校验结果。{@code ok=false} 时 {@code reason} 说明为什么（锁定 / 口令错 / 已停用）。 */
    record Check(boolean ok, String reason, boolean mustChange) {
    }

    /**
     * 校验口令。**锁定期内即使口令正确也拒** —— 否则锁形同虚设。
     * 失败累加计数、达阈值上锁；成功清零。
     *
     * @return 主体没有凭据时 {@code null} —— 调用方据此回落到既有的共享口令闸
     */
    Check verify(String realm, String subjectNo, String rawPassword);

    /**
     * 建号 / 重置口令，返回**一次性明文**。
     *
     * <p>口令由服务端生成，不接受调用方指定：让调用方定初始口令，
     * 它多半会定成「123456」或全员同一个。
     * 返回值只在这一次响应里出现 —— 不落日志、不可二次查询。
     */
    String resetPassword(String realm, String subjectNo);

    /** 本人改密。校验旧口令，通过后换新并清掉 {@code mustChange}。 */
    void changePassword(String realm, String subjectNo, String oldPassword, String newPassword);
}
