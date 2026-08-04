package ai.neargo.sharehub.cs.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 客服会话消息（cs_message，[db-design §6.5]）—— <b>append 表</b>，只 INSERT。
 *
 * <p><b>故意不继承 {@code BaseEntity}</b>：DDL 里没有 {@code version}/{@code deleted} 两列，
 * 继承会让 MyBatis-Plus 生成不存在的列。聊天记录不可改不可删是合规要求（争议时是证据），
 * 撤回只能靠再发一条状态消息表达。按 {@code created_at} 月分区。
 */
@Data
@TableName("cs_message")
public class CsMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 所属会话 {@link CsSession#getSessionNo()}。 */
    private String sessionNo;

    /** USER / AGENT。 */
    private String senderType;

    /** 发送人：USER 时是 c_user_no，AGENT 时是 employee_no。 */
    private String senderNo;

    private String content;

    /** 附件 JSON（图片 / 文件 URL 列表）。 */
    private String attach;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
