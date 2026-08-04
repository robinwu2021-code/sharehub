package ai.neargo.sharehub.alarm.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 告警通知规则（dev_alarm_rule，[db-design §3.2]）—— 防夜间轰炸与告警风暴。
 *
 * <p>比竞品多的两处能力都在列上：
 * <ul>
 *   <li><b>静默窗口</b> {@link #quietStart}/{@link #quietEnd}：{@code HH:mm} 时刻，
 *       类型是 {@code CHAR(5)} → {@code String}（[db-design §1.5] 时间列约定：
 *       时刻用 CHAR(5)，不是 TIME，也不是 DATETIME —— 它没有日期语义，跨时区只按运营时区解释）。
 *       窗口可跨零点（{@code 22:00}→{@code 07:00}），比较逻辑按字典序分段判断，见
 *       {@code AlarmRuleServiceImpl#inQuietWindow}。</li>
 *   <li><b>升级策略</b> {@link #escalateMinutes}：N 分钟未处置则升级；空或 0 = 不升级。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_alarm_rule")
public class DevAlarmRule extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    /** 业务键 AR*。 */
    private String ruleNo;

    /** 匹配告警码；空 = 匹配全部。 */
    private String alarmCode;

    /** 接收方（角色/手机号/webhook）。 */
    private String target;

    /** SMS/EMAIL/PUSH/WEBHOOK。 */
    private String channel;

    /** INSTANT/DIGEST（即时 / 汇总）。 */
    private String method;

    /** 静默窗口开始 {@code HH:mm}。 */
    private String quietStart;

    /** 静默窗口结束 {@code HH:mm}。 */
    private String quietEnd;

    /** 未处置 N 分钟后升级；空 = 不升级。 */
    private Integer escalateMinutes;

    /** ACTIVE/INACTIVE。 */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
