package ai.neargo.sharehub.agent.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理端自然人 / 登录主体（{@code agt_principal}，ADR-030 §2.2）。
 *
 * <p><b>这是「人」，{@link AgtAccount} 是「人 × 主体」的成员关系。</b>
 * 一个人在 N 个主体下就是 N 行 {@code agt_account}，但只有 <b>1 行</b> {@code agt_principal}
 * ——凭据、手机号、邮箱都挂在这里，改一处全主体生效。
 *
 * <p><b>三列分工</b>（ADR-030 §2.3，加了邮箱之后更要守住）：
 * <ul>
 *   <li>{@code *Hash}：登录查找 + 唯一约束。{@code HMAC-SHA256(规范化值, pepper)}；</li>
 *   <li>{@code *Mask}：<b>只用于显示</b>。绝不进唯一键、绝不做等值条件 ——
 *       {@code 13800138000} 与 {@code 13811138000} 的掩码相同，拿它做键会把两个人判成一个；</li>
 *   <li>{@code *Enc}：需要还原时用（发短信 / 发邮件），也是 pepper 轮换重算 hash 的唯一依据。</li>
 * </ul>
 *
 * <p>业务键前缀 {@code PR}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_principal")
public class AgtPrincipal extends BaseEntity {

    private String principalNo;

    /** HMAC(规范化手机号)；登录查找键，全局唯一。 */
    private String phoneHash;

    /** 138****8000；**仅显示**。 */
    private String phoneMask;

    /** 可逆加密的手机号明文；pb_pii 建成后迁出。 */
    private byte[] phoneEnc;

    /** HMAC(lower(trim(邮箱)))；登录查找键，全局唯一。 */
    private String emailHash;

    /** a***@example.com；**仅显示**。 */
    private String emailMask;

    private byte[] emailEnc;

    /** pepper 版本；轮换时据它判断哪些行还没重算。 */
    private Integer hashVer;

    /** {@code sharehub_auth.cred} 引用（realm=AGENT）。**一个人一套，不按主体分**。 */
    private String credRef;

    /** ACTIVE / DISABLED。存量迁移产生的占位行是 DISABLED（V47）。 */
    private String status;
}
