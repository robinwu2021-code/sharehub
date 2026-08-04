package ai.neargo.sharehub.trade.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * nearpay 结果事件留痕（pay_event_log，[db-design §5.3]）。
 *
 * <p><b>append 表</b>：DDL 无 {@code version}/{@code deleted}/{@code tenant_id}，
 * 故**不继承** {@code BaseEntity}（骨架规约 §4）。只插不改不删。
 *
 * <p><b>回调幂等的落点</b>（[db-design §1.6]）：UK({@code ref_no}, {@code event_type})。
 * 回调处理器进来第一件事是 {@code PayEventLogService#alreadyProcessed(refNo, eventType)}，
 * 命中即直接返回成功 —— nearpay 重放同一事件不得二次改动订单/资金。
 */
@Data
@TableName("pay_event_log")
public class PayEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件来源，目前恒为 NEARPAY。 */
    private String source;

    /** 对侧单号（nearpay txn / auth / refund no）。 */
    private String refNo;

    private String eventType;

    /** 原始报文（JSON 列，按字符串收，不做结构约束）。 */
    private String raw;

    /** 是否已被业务处理（TINYINT(1) → Integer）。 */
    private Integer processed;

    private String receivedAt;

    /** 服务端落库时刻（与 receivedAt 的差值可诊断时钟偏移/链路延迟）。由 AuditMetaObjectHandler 填充。 */

    @TableField(fill = FieldFill.INSERT)

    private java.time.LocalDateTime createdAt;

    /** 创建人（append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
