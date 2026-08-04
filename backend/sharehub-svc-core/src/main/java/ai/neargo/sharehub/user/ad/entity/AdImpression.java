package ai.neargo.sharehub.user.ad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 广告曝光统计（ad_impression，[db-design §6.4]）—— <b>append 表</b>，只 INSERT 不 UPDATE/DELETE。
 *
 * <p><b>故意不继承 {@code BaseEntity}</b>：[db-design §10] 把本表与 {@code acct_ledger}/
 * {@code usr_wallet_txn}/{@code notify_log} 一并归入「只增流水」，DDL 里没有 {@code version}/{@code deleted}
 * 两列 —— 继承基类会让 MyBatis-Plus 生成不存在的列并在乐观锁/逻辑删除拦截器上炸掉。
 * 按月分区、保留 7 年。
 *
 * <p>粒度是「一个投放 × 一个广告位 × 一天」的聚合行（{@code impressions}/{@code plays}/{@code statDate}），
 * 不是逐次播放明细 —— 逐次落库在设备投屏场景写入量不可控。
 */
@Data
@TableName("ad_impression")
public class AdImpression {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 曝光统计行业务键，前缀 {@code DLV}。 */
    private String deliveryNo;

    private String adNo;

    private String slotNo;

    private String cabinetNo;

    /** 曝光次数。 */
    private Integer impressions;

    /** 完整播放次数（≤ impressions）。 */
    private Integer plays;

    /** 统计日 {@code YYYY-MM-DD}（日期语义，[db-design §1.5] 用 DATE）。 */
    private String statDate;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
