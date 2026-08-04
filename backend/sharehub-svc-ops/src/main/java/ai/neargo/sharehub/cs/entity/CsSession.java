package ai.neargo.sharehub.cs.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 客服会话（cs_session，[db-design §6.5]，C-CS-04）—— 人工客服的对话主体，消息明细在 {@link CsMessage}。
 *
 * <p>{@code lastMessage} 是最后一条消息的摘要冗余，让会话列表无需 JOIN append 表即可直出；
 * {@code agentName} 是坐席姓名快照（[db-design §1.4]：不随源改名回溯）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cs_session")
public class CsSession extends BaseEntity {

    private String sessionNo;

    private String cUserNo;

    /** 客服坐席快照名。 */
    private String agentName;

    /** 最后一条消息摘要（列表直出）。 */
    private String lastMessage;

    /** ACTIVE / CLOSED。 */
    private String status;
}
