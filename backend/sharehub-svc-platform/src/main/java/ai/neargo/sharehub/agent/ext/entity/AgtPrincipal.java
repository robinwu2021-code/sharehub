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
 * <p><b>两列分工</b>（ADR-030 §2.3；原本写的是三列，第三列 V130 已删 —— 见下）：
 * <ul>
 *   <li>{@code *Hash}：登录查找 + 唯一约束。{@code HMAC-SHA256(规范化值, pepper)}；</li>
 *   <li>{@code *Mask}：<b>只用于显示</b>。绝不进唯一键、绝不做等值条件 ——
 *       {@code 13800138000} 与 {@code 13811138000} 的掩码相同，拿它做键会把两个人判成一个。</li>
 * </ul>
 *
 * <p><b>原来还有第三列 {@code *Enc}</b>（「需要还原时用，也是 pepper 轮换重算 hash 的唯一依据」），
 * 但它**从建表起就没有任何一处读或写**，生产上恒为 NULL；{@code iam_employee} 那一侧
 * 连这两列都没有。所以「轮换」这个能力从来不存在，只存在于注释里。V130 把列删了，
 * 并把说法改成实话（见 {@code IdentifierHasher} 的类注释）。
 *
 * <p>要联系方式请看 {@code agt_agent.contact_name/phone/email}（V127）——
 * 那是**业务联系方式**，与登录标识是两回事。
 *
 * <p>业务键前缀 {@code PR}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_principal")
public class AgtPrincipal extends BaseEntity {

    private String principalNo;

    /** HMAC(规范化手机号)；查找键。V168 起可空（手机号选填），唯一性只对已验证的生效。 */
    private String phoneHash;

    /** 138****8000；**仅显示**。 */
    private String phoneMask;

    /**
     * 手机号已由本人验证（V168）。运营录入的一律先记 0 —— 录错一位就把账号交给陌生人（R3）；
     * 只有已验证的手机号可以登录（R5）、才占唯一（{@link #phoneUk}）。
     */
    private Integer phoneVerified;

    /** 生成列：已验证取 phoneHash，否则 NULL —— 唯一键挂这里，未验证的不挡号主（R6）。只读。 */
    @com.baomidou.mybatisplus.annotation.TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String phoneUk;


    /** HMAC(lower(trim(邮箱)))；主登录查找键。唯一性只对已验证的生效（V168）。 */
    private String emailHash;

    /** a***@example.com；**仅显示**。 */
    private String emailMask;

    /** 邮箱已由本人验证（V168）：邮箱验证码首登成功即置 1（R4）。 */
    private Integer emailVerified;

    /** 生成列：已验证取 emailHash，否则 NULL。只读。 */
    @com.baomidou.mybatisplus.annotation.TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String emailUk;


    /** pepper 版本；轮换时据它判断哪些行还没重算。 */
    private Integer hashVer;

    /** {@code sharehub_auth.cred} 引用（realm=AGENT）。**一个人一套，不按主体分**。 */
    private String credRef;

    /** ACTIVE / DISABLED。存量迁移产生的占位行是 DISABLED（V47）。 */
    private String status;
}
