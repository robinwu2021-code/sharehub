package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 调拨单（inv_transfer，[db-design §3.3]）—— 聚合根，状态 {@code DRAFT → IN_TRANSIT → DONE}。
 *
 * <p><b>调拨两端是「类型 + 引用 + 快照名」三件套</b>，不是一个字符串：
 * {@link #fromType}/{@link #toType} ∈ {@code WAREHOUSE/SITE/LOCATION} 决定 {@link #fromRef}/{@link #toRef}
 * 指向哪张表；{@link #fromName}/{@link #toName} 是**写入时快照**，供列表直接渲染，
 * 站点后来改名也不回溯（改了名的历史单据应显示当时的名字）。
 *
 * <p>⚠️ <b>与 DDL 不一致（已知，待主控裁决）</b>：{@code ddl/pb_core-v2-ops-alarm.sql} 里
 * {@code inv_transfer} 只有 {@code from_location}/{@code to_location}/{@code operator} 三列（v1 形态，
 * 与前端 {@code InventoryTransfer} 一致）。本实体按 [db-design §3.3] 的 v2 列清单建
 * （规约指定 db-design 为字段 SSOT），DDL 需补 {@code ALTER}：
 * {@code from_type/to_type/from_ref/to_ref/from_name/to_name/operator_no}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_transfer")
public class InvTransfer extends BaseEntity {

    /** 业务键 TR*。 */
    private String transferNo;

    /** 调出方类型：WAREHOUSE/SITE/LOCATION。 */
    private String fromType;

    /** 调出方业务键（按 {@link #fromType} 解释）。 */
    private String fromRef;

    /** 调出方名称快照（写入时定格，不回溯）。 */
    private String fromName;

    /** 调入方类型：WAREHOUSE/SITE/LOCATION。 */
    private String toType;

    /** 调入方业务键（按 {@link #toType} 解释）。 */
    private String toRef;

    /** 调入方名称快照。 */
    private String toName;

    /** CABINET/POWERBANK。 */
    private String itemType;

    /** 调拨件数（列名沿用 db-design 的 {@code powerbank_count}，机柜调拨时同样计件）。 */
    private Integer powerbankCount;

    /** DRAFT/IN_TRANSIT/DONE。 */
    private String status;

    /** 经办人（employee_no）。 */
    private String operatorNo;

    // —— 批次 C（V107）：来源与签收 ——
    /** MANUAL / REMOVAL（{@link ai.neargo.sharehub.inv.TransferSource}）。 */
    private String sourceType;
    /** REMOVAL 时为撤机工单号（幂等键：一张撤机单只生成一张回仓单）。 */
    private String sourceRef;
    private java.time.LocalDateTime shippedAt;
    private java.time.LocalDateTime receivedAt;
    private String receivedBy;
    private String receiveNote;
}
