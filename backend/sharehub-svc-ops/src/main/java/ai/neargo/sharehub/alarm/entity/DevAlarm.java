package ai.neargo.sharehub.alarm.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 告警记录（dev_alarm，[db-design §3.2]）—— 聚合根，v1 {@code dev_alert} 改造并更名。
 *
 * <p><b>多厂商错误码归一化</b>（本模块相对竞品的第一处优势，落在列上）：
 * {@link #alarmCode} 是**平台统一码**（→ {@code dev_alarm_code.code}），
 * {@link #vendorErrorCode} 是**厂商原始码**（{@code E203} / {@code ERR-17} / {@code 0x1F04} 各家风格不同）。
 * 两列都留：统一码供规则匹配与报表聚合，原始码供对厂商排障与回溯，缺任一边都会断链。
 *
 * <p><b>转工单幂等</b>：{@link #woNo} 非空即表示已开单；幂等硬约束落在 {@code wo_order.source_ref}
 * 的 UNIQUE(alarmNo) 上（[db-design §1.6]），本列是它的正向缓存，先查它可免掉一次撞 UNIQUE。
 *
 * <p>{@code siteNo}/{@code agentNo} 是数据权限冗余列（ADR-012），联合索引
 * {@code (tenant_id, agent_no, status, occurred_at)} 让「数据范围过滤 + 默认排序」一次走完。
 *
 * <p>{@code level} 与 {@code dev_alarm_code.level} 双份是有意的：本列是**写入时快照**（可被规则覆盖），
 * 字典 level 只是默认值（[db-design §十三] 已登记此待确认项）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_alarm")
public class DevAlarm extends BaseEntity {

    /** 业务键 ALM*。 */
    private String alarmNo;

    private String cabinetNo;

    /** 站点归属（冗余·数据权限）。 */
    private String siteNo;

    /** 归属代理（冗余·数据权限 ADR-012）。 */
    private String agentNo;

    private String vendorCode;

    /** 平台统一告警码 → {@link DevAlarmCode#getCode()}。 */
    private String alarmCode;

    /** 厂商原始错误码（归一化前的原文）。 */
    private String vendorErrorCode;

    /** INFO/WARN/CRITICAL。 */
    private String level;

    /** DEVICE/OTA/RENT。 */
    private String source;

    /** 发生时刻（列表默认排序列）。 */
    private String occurredAt;

    /** OPEN/ACKED/CLOSED。 */
    private String status;

    /** 关联工单号；非空 = 已转工单（幂等正向缓存，见类注释）。 */
    private String woNo;

    private String remark;

    /** 去重键：同源重复告警合并计数而非刷屏。 */
    private String dedupKey;

    /** 合并次数。 */
    private Integer count;
}
