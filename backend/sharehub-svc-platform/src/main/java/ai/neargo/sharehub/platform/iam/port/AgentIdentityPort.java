package ai.neargo.sharehub.platform.iam.port;

import java.util.List;

/**
 * 代理端身份解析端口（必要功能清单 ④⑤ / ADR-030）。
 *
 * <h2>为什么接口在 platform，实现在 agent</h2>
 * {@code AuthController} 属 {@code platform} 切片，它需要「按手机号找到人、列出这个人的主体」。
 * 但<b>直接依赖 {@code agent} 会成环</b>：已有 {@code agent → loc}（AgentOwnershipSync 读站点）
 * 与 {@code loc → platform}（LocService 读地区），再加一条 {@code platform → agent}
 * 就闭合成 {@code agent → loc → platform → agent} —— ArchUnit 的 noCyclesBetweenDomains 会红。
 *
 * <p>所以按依赖倒置把方向翻过来：<b>消费方声明需要什么</b>（本接口），供给方去实现
 * （{@code agent.ext.service.impl.AgentLoginServiceImpl}）。边变成 {@code agent → platform}，
 * 而 platform 不依赖任何业务域、是汇点，图上不可能再成环。
 * 这与本仓既有的 {@code OtpGate} 是同一手法，只是方向相反。
 *
 * <h2>为什么只支持「手机号 + 验证码」</h2>
 * <ul>
 *   <li><b>口令</b>：全仓没有凭据存储（{@code iam_user} 无口令列，{@code cred_credential} 在
 *       V4 里是注释掉的设计，归属 {@code pb_auth}/auth-core）。现在另起一张平行的凭据表，
 *       auth-core 落地时要做数据迁移 —— 而 OTP 登录本身就是完整可用的登录方式，不是权宜之计。</li>
 *   <li><b>邮箱</b>：发码要邮箱明文，而 {@code activate()} 只写了 {@code email_hash}/{@code email_mask}，
 *       {@code email_enc} 是空的、掩码不可逆 —— 发不出去。手机号同理拿不到明文，
 *       所以必须由用户<b>自己输入手机号</b>，服务端规范化后按 hash 反查。</li>
 * </ul>
 */
public interface AgentIdentityPort {

    /**
     * 「我属于哪个运营主体」——{@code agt_account} 成员关系行（ADR-030 §2.2）。
     *
     * <p>一个自然人可以有多条：给两家运营商干活就是两行。
     *
     * @param isOwner   主体属主：全站点全权限、不进授权表（ADR-030 §5.1）
     * @param isPrimary 该人的默认主体；登录不指定主体时进这一个
     */
    record OperatorMembership(String agentNo, String agentName, String accountNo,
                              String displayName, Boolean isOwner, Boolean isPrimary,
                              String status) {
    }


    /**
     * 发送登录验证码。
     *
     * <p><b>不泄露「这个号存不存在」</b>：查无此人时静默返回 null 而不抛错。
     * 否则这个接口就成了「批量探测哪些手机号是本平台代理商」的工具。
     *
     * @return 验证码本身，<b>仅 dev-mode 下由调用方回显</b>；生产走短信通道，接口不回传
     */
    String sendLoginOtp(String rawPhone);

    /**
     * 校验手机号 + 验证码，返回该自然人的 {@code principal_no}。
     *
     * <p>失败一律抛 {@link IllegalArgumentException}，且<b>文案不区分「号不存在」与「码不对」</b>
     * —— 区分了就等于送出一个账号枚举接口。
     */
    String verifyLoginOtp(String rawPhone, String otp);

    /**
     * 该自然人名下的运营主体（{@code agt_account} 成员关系行）。
     *
     * <p>这就是 ADR-030「一个账号多主体」在 API 上的兑现：数据结构早就支持，
     * 但此前<b>没有任何端点</b>把它告诉前端，于是一人服务两家运营商时登录后无从选择。
     */
    List<OperatorMembership> memberships(String principalNo);

    /**
     * 取该自然人的默认主体：{@code is_primary} 优先，否则第一条可用的。
     *
     * @throws IllegalStateException 一个主体都没有（入驻未通过 / 全部停用）——
     *                               这时<b>不能发 token</b>，否则会放进一个什么都看不到的空壳会话
     */
    OperatorMembership primaryOf(String principalNo);

    /** 校验该自然人确实属于该主体，用于切换主体时防止越权切到别人家。 */
    OperatorMembership requireMembership(String principalNo, String agentNo);
}
