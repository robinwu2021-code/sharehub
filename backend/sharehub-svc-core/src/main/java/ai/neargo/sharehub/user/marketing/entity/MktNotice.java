package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运营公告（mkt_notice，[db-design §6.3]）—— **C 端首页公告条的唯一发布口**。
 *
 * <p>v1 的整体遗漏：c-app 首页 Hub 已有公告条，运营端却没有发布入口。v2 补齐，并且比竞品多三样：
 * <ol>
 *   <li><b>三语三列</b>（[db-design §1.5]）：{@code title/title_en/title_ar} +
 *       {@code content/content_en/content_ar}。不用 JSON —— 前端按列取值且需要按列检索；</li>
 *   <li><b>生效期</b> {@code startAt}/{@code endAt}：到点自动上下线，不靠人工改状态；</li>
 *   <li><b>置顶</b> {@code pinned}：C 端公告条优先展示。</li>
 * </ol>
 *
 * <p>{@code pinned} 是 TINYINT(1)，实体侧按约定用 {@code Integer}（1=置顶），VO 层转 boolean。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_notice")
public class MktNotice extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    private String noticeNo;

    private String title;
    private String titleEn;
    private String titleAr;

    private String content;
    private String contentEn;
    private String contentAr;

    /** SYSTEM / PROMO / MAINTENANCE。 */
    private String type;

    /** 置顶标记（TINYINT(1)：1=置顶）。 */
    private Integer pinned;

    /** 生效起；空 = 立即生效。 */
    private String startAt;

    /** 生效止；空 = 长期有效。 */
    private String endAt;

    /** DRAFT / PUBLISHED / OFFLINE。C 端只读 PUBLISHED 且在生效期内的。 */
    private String status;

    /** 发布人 employee_no（快照，不随改名回溯）。 */
    private String publishedBy;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
