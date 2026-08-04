package ai.neargo.sharehub.user.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 同意与撤回留痕（usr_consent，[db-design §6.6]，C-AC-06）—— PDPL 可举证要求。
 *
 * <p><b>append + WORM 表</b>：只 INSERT，无 UPDATE/DELETE 授权，故<b>不继承 {@code BaseEntity}</b>
 * （DDL 里没有 {@code version}/{@code deleted}/{@code updated_at}）。撤回同意 = 再插一行 {@code action=REVOKE}，
 * 不是改上一行。
 *
 * <p><b>版本列叫 {@code agreementVersion} 不叫 {@code version}</b>：{@code version} 是
 * {@code BaseEntity} 的乐观锁列名，同名会在将来任何一次「让本表继承基类」的重构里静默撞车。
 */
@Data
@TableName("usr_consent")
public class UsrConsent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String cUserNo;

    /** 协议标识 TOS / PRIVACY / MARKETING / …。 */
    private String agreementCode;

    /** 协议版本。<b>不可改名为 {@code version}</b>，见类注释。 */
    private String agreementVersion;

    /** GRANT / REVOKE。 */
    private String action;

    /** zh / en / ar。 */
    private String lang;

    private String ip;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
