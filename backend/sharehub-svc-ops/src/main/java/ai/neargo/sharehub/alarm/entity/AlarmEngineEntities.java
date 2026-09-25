package ai.neargo.sharehub.alarm.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 业务告警引擎的工作表与从表（V98）。 */
public final class AlarmEngineEntities {

    private AlarmEngineEntities() {
    }

    /** 根因路由（全局表）：同一业务告警，根因不同派的活不同。cause='*' 兜底。 */
    @Data
    @TableName("dev_alarm_route")
    public static class DevAlarmRoute {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String alarmCode;
        private String cause;
        private String disposition;
        private String woType;
        private Integer priorityDelta;
        private String fallback;
        private LocalDateTime createdAt;
        private String createdBy;
        private LocalDateTime updatedAt;
        private String updatedBy;
        @Version
        private Long version;
        @TableLogic
        private Integer deleted;
    }

    /** 持续条件（工作表，可随时清空重建）：告警成立前的「计时中」。 */
    @Data
    @TableName("dev_alarm_condition")
    public static class DevAlarmCondition {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String dedupKey;
        private String alarmCode;
        private String subjectSite;
        private LocalDateTime firstSeenAt;
        private LocalDateTime lastSeenAt;
        private Integer heldMinutes;
    }

    /** 告警时间线（追加）。 */
    @Data
    @TableName("dev_alarm_log")
    public static class DevAlarmLog {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String alarmNo;
        private String event;
        private String note;
        private String operator;
        private LocalDateTime createdAt;
        private String createdBy;
    }

    /** 告警待办。open_alarm_no 是生成列（一条告警同时最多一条未完成待办），不映射。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("dev_alarm_todo")
    public static class DevAlarmTodo extends BaseEntity {
        private String todoNo;
        private String alarmNo;
        private String roleCode;
        private String assigneeNo;
        private String title;
        private String status;
        private LocalDateTime doneAt;
        private String doneBy;
        private String doneNote;
        private String siteNo;
        private String agentNo;
    }

    /** 告警用站点画像（派生，每日重算）。 */
    @Data
    @TableName("dev_alarm_site_profile")
    public static class DevAlarmSiteProfile {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String siteNo;
        private String tier;
        private String peakHours;
        @com.baomidou.mybatisplus.annotation.TableField("gmv_30d")   // 驼峰转下划线不会在数字前断开
        private BigDecimal gmv30d;
        private LocalDateTime computedAt;
    }
}
