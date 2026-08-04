package ai.neargo.sharehub.gw.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 心跳遥测（dev_heartbeat，[db-design §3.1]）。
 *
 * <p><b>append 表</b>（按 {@code beat_at} 月分区，热 3 月，[db-design §十]）：
 * 只插不改不删，故<b>不继承 BaseEntity</b>，无 {@code version}/{@code deleted}。
 */
@Data
@TableName("dev_heartbeat")
public class DevHeartbeat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cabinetNo;

    /** fw / 信号 / 温度 / 电量等指标的 JSON 原文。 */
    private String metrics;

    private String beatAt;

    /** 服务端落库时刻（与 beatAt 的差值可诊断时钟偏移/链路延迟）。由 AuditMetaObjectHandler 填充。 */

    @TableField(fill = FieldFill.INSERT)

    private java.time.LocalDateTime createdAt;

    /** 创建人（append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
