package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站内消息中心（usr_message，[db-design §6.6]，C-MS-03）。
 *
 * <p><b>字段名 {@code isRead} 而非 {@code read}</b>：{@code read} 是 MySQL 保留字，
 * 列名必须是 {@code is_read}；实体字段跟着叫 {@code isRead}，避免 MP 驼峰映射拼出 {@code read} 列。
 * 出参 VO 里才转回业务语义的 {@code read}（[UserCoreDtos.MessageItem]）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_message")
public class UsrMessage extends BaseEntity {

    private String messageNo;

    private String cUserNo;

    /** ORDER / WALLET / COUPON / SYSTEM / ACTIVITY / CS。 */
    private String type;

    /*
     * 用户可见文本走**三语三列**（[db-design §1.5]，V133 补齐）。
     *
     * 单列存中文的后果是阿语界面上出现一句中文，而不会有任何地方报错 ——
     * FeeItem（费用明细曾返文案）与公告（改成三语全量下发）都为同一个错改过一轮。
     * db-design 正文给本表只定了 title/body 两列，那是规范自己违反了自己的 §1.5。
     */
    private String title;

    private String titleEn;

    private String titleAr;

    private String body;

    private String bodyEn;

    private String bodyAr;

    /** TINYINT(1) → Integer；列名 is_read（read 是保留字）。 */
    private Integer isRead;

    /** 空 = 尚未读。 */
    private String readAt;
}
