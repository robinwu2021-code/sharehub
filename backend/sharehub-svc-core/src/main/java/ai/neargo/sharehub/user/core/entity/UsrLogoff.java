package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 注销申请（usr_logoff，[db-design §6.6]，C-AC-05）—— <b>PDPL 冷静期</b>三件套之一。
 *
 * <p>状态机：{@code PENDING}（冷静期内，用户可自行撤销）
 * → {@code CANCELLED}（用户撤回申请）| {@code DONE}（{@code coolingUntil} 到期，执行清除后回填 {@code purgedAt}）。
 *
 * <p><b>不设 UK(c_user_no)</b>：撤销后允许再次申请；「同一用户至多一条 PENDING」由应用层保证
 * （见 {@code UserLogoffService#apply}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_logoff")
public class UsrLogoff extends BaseEntity {

    private String cUserNo;

    private String requestedAt;

    /** 冷静期截止；期内可撤销。 */
    private String coolingUntil;

    /** PENDING / CANCELLED / DONE。 */
    private String status;

    /** 空 = 尚未清除。 */
    private String purgedAt;
}
