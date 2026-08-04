package ai.neargo.sharehub.platform.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发送记录（notify_log）—— **append 表**，按 {@code created_at} 月分区、保留 7 年，[db-design §2.3]。
 *
 * <p>append 表在 DDL 里没有 {@code version}/{@code deleted}，故**不继承 BaseEntity**：
 * 发出去的消息不能"改成没发过"，也没有并发改的场景。
 *
 * <p>两个必须记住的字段语义：
 * <ul>
 *   <li>{@code target} <b>存储即脱敏</b>（[api/README §1.6]）——明文联系方式不入本表，
 *       写库前一律过 {@code NotifyTargets.maskTarget}。发送记录是运营人员日常翻看的页面，
 *       在这里留明文等于把全量用户联系方式摊开。</li>
 *   <li>{@code cost} 是 {@code DECIMAL(18,4)}，[db-design §1.5]「金额一律两位」的**唯一例外**：
 *       单条短信可能是 0.0035 AED，两位小数会把成本全抹成 0，触达成本就核算不出来。</li>
 * </ul>
 */
@Data
@TableName("notify_log")
public class NotifyLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务键，前缀 {@code NL}（{@code BizKey.NOTIFY_LOG}）。 */
    private String logNo;
    private String tenantId;
    /** SMS / EMAIL / PUSH / WHATSAPP */
    private String channel;
    private String templateNo;
    /** 接收方，**已脱敏**（手机留前 6 后 2，邮箱留首字母 + 域名）。 */
    private String target;
    private String scene;
    /** 实际发出时刻（空 = 尚未发生）。 */
    private String sentAt;
    /** SENT / FAILED */
    private String status;
    private String failReason;
    /** 单条触达成本，4 位小数（见类注释）。 */
    private BigDecimal cost;
    private String currency;

    /**
     * 幂等键。重发/试发**必带** —— 同键第二次由 UNIQUE 约束拒绝。
     * 靠应用层判重在并发下会漏，所以执行手段是数据库唯一索引。历史 seed 为 null。
     */
    private String idempotencyKey;

    /** 由哪条记录重发而来；null = 原始发送。**重发是新增一条，原记录一字不改**（审计要看得见发了两次）。 */
    private String resendOf;
    private LocalDateTime createdAt;
    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
