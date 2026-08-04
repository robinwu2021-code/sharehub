package ai.neargo.sharehub.platform.notify.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知模板（notify_template）—— 多通道 + 多语，[db-design §2.3]。
 *
 * <p><b>为什么这里用 {@code lang} 行、而不是三语三列</b>：[db-design §1.5] 的三语约定
 * （{@code xxx}/{@code xxx_en}/{@code xxx_ar}）只适用于**语种固定**的用户可见文本；
 * 模板是明确会扩语种的场景（开一个国家就多一门语言），列会无限长，故按行区分 ——
 * 这是该约定里写明的唯一例外。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notify_template")
public class NotifyTemplate extends BaseEntity {
    private String templateNo;
    private String name;
    /** SMS / EMAIL / PUSH / WHATSAPP */
    private String channel;
    /** ar / en / zh */
    private String lang;
    /** 业务场景码，如 OTP / ORDER_DONE / ALARM。 */
    private String scene;
    private String content;
    /** 可填充变量清单（JSON 文本）。 */
    private String params;
    /** ENABLED / DISABLED */
    private String status;
}
