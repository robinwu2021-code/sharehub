package ai.neargo.sharehub.agent.ext.service;

/**
 * 「让某个人成为某个代理主体的成员」—— 自然人 + 成员关系的**唯一**建立点。
 *
 * <h2>为什么要有这个服务</h2>
 * 代理端的登录链是
 * <pre>手机号 → agt_principal.phone_hash → agt_account.principal_no → agent_no</pre>
 * 而运营端此前的两个入口**都不产生 {@code principal_no}**：
 * <ul>
 *   <li>「新增代理商」只写 {@code agt_agent}，自然人与成员关系一行都不建；</li>
 *   <li>「新增代理账号」的 {@code beforeCreate} 只要求 {@code agentNo}，
 *       从不设 {@code principal_no} —— 行建出来了、列表里看得见，
 *       登录时按自然人查不到它。</li>
 * </ul>
 * 于是运营能建代理商、能给它开账号、能划拨资产、能配分润，
 * <b>唯独那个代理商自己永远进不来</b>，而且两处都不报错。
 *
 * <p>正确的做法本来就写在 {@code ApplyServiceImpl.activate()} 的 ②③ 步里
 * （入驻审核通过那条路）。这里把它抽出来，让运营端的入口**复用同一份实现** ——
 * 两份实现意味着「在哪个入口建的，决定他能不能登录」，而那种差异没有症状。
 */
public interface AgentMembershipService {

    /**
     * 让持有 {@code rawPhone} 的自然人成为 {@code agentNo} 的**属主**成员。
     *
     * <p>幂等：这个人已经是该主体的成员时直接返回既有的成员关系号，不重复建。
     *
     * @param rawPhone 未规范化的手机号（会走 {@code IdentifierNormalizer}）；
     *                 **空则用主体档案上的联系手机** —— 常态就是用他档案上那个号
     * @param rawEmail 同上回落到档案邮箱。**不可空** —— `agt_principal.email_hash`
     *                 是 NOT NULL + UNIQUE，自然人有两个登录标识，缺一个建不出来
     * @param displayName 在该主体下的显示名；空则回落联系人姓名、再回落主体名
     * @return 成员关系号（{@code agt_account.account_no}）
     */
    String grantOwner(String agentNo, String rawPhone, String rawEmail, String displayName);
}
