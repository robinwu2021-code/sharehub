package ai.neargo.sharehub.alarm.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 告警代码字典（dev_alarm_code，[db-design §3.2]）——**字典即处置预案**。
 *
 * <p><b>全局表，无 {@code tenant_id}</b>（[db-design §1.4] 全局表放行不注入租户）：
 * 平台统一码是跨租户的归一化词表，租户各自定义就失去归一化意义。
 * {@code code} 是**自然键**（对外有语义），不走前缀取号。
 * ⚠️ 已知取舍：租户无法自定义告警码，只能改通知规则；单租户 MVP 无影响，
 * 启用多租户前必须复核（[db-design §十三]）。
 *
 * <p>比竞品多的两列都在这里：{@link #suggestion}（建议处置，让一线不必回问）与
 * {@link #autoWorkOrder}（命中即自动开单，把「告警→通知」的断链接成「告警→工单」）。
 *
 * <p><b>继承 {@code BaseEntity} 带来的 {@code tenantId} 字段在本表无对应列</b>——
 * 与既有全局表 {@code md_bank}/{@link ai.neargo.sharehub.platform.md.entity.MdBank} 的处理一致，
 * 保持样板统一；若将来 MP 因缺列报错，应在 BaseEntity 层统一解，而非逐表打补丁。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "dev_alarm_code", excludeProperty = "tenantId")
public class DevAlarmCode extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    /** 平台统一告警码（自然键），如 {@code SLOT_STUCK}。 */
    private String code;

    /** 描述（默认语）。三语三列见 [db-design §1.5]。 */
    private String message;

    private String messageEn;

    private String messageAr;

    /** INFO/WARN/CRITICAL —— 这里是**默认值**，写入 {@code dev_alarm.level} 时取快照。 */
    private String level;

    /** 建议处置（预案）。 */
    private String suggestion;

    /** 是否自动开工单（TINYINT(1) → Integer）。 */
    private Integer autoWorkOrder;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
