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
 * <p>⚠️ <b>没有 {@code data_scope} 列，且不要加</b>：前端 {@code AgentAccount.dataScope} 的落点是
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

    /** 登录手机号（掩码；明文落 pb_pii）。 */
    private String loginPhone;

    /** {@code pb_auth.cred} 引用（realm=AGENT）；本表不存口令。 */
    private String credRef;

    /** ACTIVE / DISABLED。 */
    private String status;
}
