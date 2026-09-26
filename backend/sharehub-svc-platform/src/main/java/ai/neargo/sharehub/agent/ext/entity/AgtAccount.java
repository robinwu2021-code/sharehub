package ai.neargo.sharehub.agent.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理登录账号（agt_account，[db-design §3.5]）。
 *
 * <p>代理商是运营方**体内经营伙伴、非租户**（ADR-012），但需要独立登录 —— 凭据落
 * {@code pb_auth.cred}（{@code realm=AGENT}，[db-design §七]），本表只存 {@link #credRef} 引用，
 * <b>不存任何口令материал</b>。
 *
 * <p>⚠️ <b>没有 {@code data_scope} 列，且不要加</b>（但也别照下面这句去查 —— {@code AGENT_ACCOUNT}
 * 还不在 {@code DataScopeSubject} 词表里，那张表里没有这个主体的行；详见
 * {@code AgentAccountServiceImpl#toVO}）：前端 {@code AgentAccount.dataScope} 的落点是
 * {@code iam_data_scope}（{@code subject_type='AGENT_ACCOUNT'}, {@code subject_no=account_no}），
 * 见 [db-design §1.7] 多值列拆表。理由是数据范围本身是 {@code scope_type + scope_refs} 的组合值
 * （ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF ×  refs 列表），单列存不下；而且角色级/员工级/代理级
 * 三套范围必须同一张表同一套解析逻辑，否则 {@code DataScopeHandler} 要为代理再写一条分支。
 *
 * <p>业务键前缀 {@code AA}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_account")
public class AgtAccount extends BaseEntity {

    private String accountNo;

    private String agentNo;

    /** 冗余展示名（写入时快照，不随源改名回溯，[db-design §1.4]）。 */
    private String agentName;

    /** 登录用户名。 */
    private String username;

    /**
     * 登录手机号（<b>掩码</b>；V13 列注释原文「明文脱敏值」）。
     *
     * <p>⚠️ <b>它不是登录键，也永远不能当登录键用</b>：{@code 13800138000} 与
     * {@code 13811138000} 的掩码都是 {@code 138****8000} —— 拿它做唯一键会把两个不同的人
     * 判成重复；掩码不可逆，按它查找等于前缀模糊匹配。
     * 登录标识在 {@link AgtPrincipal} 的 {@code phoneHash} / {@code emailHash} 上（ADR-030 §2.3）。
     *
     * <p>留着只为显示。新代码请读 {@code AgtPrincipal.phoneMask}。
     */
    private String loginPhone;

    /**
     * → {@link AgtPrincipal#getPrincipalNo()}。<b>本表的真正主语</b>（V47 起）。
     *
     * <p>唯一键 {@code uk_agt_account_member (agent_no, principal_no)} 取代了原来的
     * {@code uk_agt_username} —— 后者是「一个人只能属于一个主体」这条限制的来源。
     */
    private String principalNo;

    /**
     * 是否主体属主。
     *
     * <p>属主 = 该主体名下<b>全部站点</b>，<b>不进授权表</b>（ADR-030 §5.1）。
     * 反过来做（把权限逐项列给属主）会死锁：上一个新功能，老板被锁在外面，
     * 而他是唯一能给自己授权的人。
     */
    private Integer isOwner;

    /** 是否这个人的默认主体（登录后默认进哪个）。<b>同一人至多一个</b>，否则登录进哪个主体不确定。 */
    private Integer isPrimary;

    /** 在该主体下的显示名 —— 同一个人在不同主体下可以有不同称呼。 */
    private String displayName;

    /** {@code pb_auth.cred} 引用（realm=AGENT）；本表不存口令。 */
    private String credRef;

    /** ACTIVE / DISABLED。 */
    private String status;
}
