package ai.neargo.sharehub.wo.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 巡检计划（wo_inspection_plan，[db-design §3.6]）。
 *
 * <p>纯配置：一条计划 = 一条路线 + 一个频率 + 一个执行人。到点由调度器按 {@link #cron}
 * 开出 {@code type=INSPECT} 的工单（{@code wo_order}），本表自身无状态机 → 走通用 CRUD。
 *
 * <p>业务键前缀 {@code IP}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wo_inspection_plan")
public class WoInspectionPlan extends BaseEntity {

    private String planNo;

    /** 站点/点位路线的 JSON 原文。 */
    private String route;

    /** 频率的人读描述（DAILY / WEEKLY / MONTHLY…），与 {@link #cron} 互为展示/执行两面。 */
    private String frequency;

    /** 调度表达式（执行口径以此为准）。 */
    private String cron;

    /** 下次执行时刻；由调度器回写。 */
    private String nextAt;

    /** 执行人 employee_no。DDL 实际列名 {@code assignee_id}。 */
    @TableField("assignee_id")
    private String assigneeNo;

    /** 是否启用（0/1）。 */
    private Integer active;

    /** 上次执行时间（V31）。 */
    private java.time.LocalDateTime lastRunAt;

    /** 上次执行覆盖的周期键（V31，幂等键——同周期第二次执行拒）。 */
    private String lastRunPeriod;

    /** 上次执行产出的工单号，逗号分隔（V31）。 */
    private String lastRunWoNos;
}
