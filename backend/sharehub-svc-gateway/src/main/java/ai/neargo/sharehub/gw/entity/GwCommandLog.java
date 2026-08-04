package ai.neargo.sharehub.gw.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指令下发日志（gw_command_log，[db-design §四]）—— ★ **指令幂等源**。
 *
 * <p>{@link #commandId} 上有 UNIQUE（[db-design §1.6]），它防的是**重复弹出**：
 * 网络抖动导致的重发、用户连点、订单侧重试，都必须带同一个 {@code commandId} 落到同一行，
 * 否则用户付一笔钱弹出两个充电宝。运行期还有 Redis SETNX 做前置去重，
 * 但**权威幂等在这条 UNIQUE 上** —— Redis 会丢，表不会。
 *
 * <p>append 表（月分区），无 {@code version}/{@code deleted}，故不继承 {@code BaseEntity}：
 * 指令发过就是发过，既不该软删也不该乐观锁改写；状态推进（PENDING→SENT→ACKED）是按主键更新状态列，
 * 不是改写事实。
 *
 * <p>本表还是「设备日志」读模型的下行半边：与 {@link GwMessageLog}(UP) 按 {@code occurred_at}
 * UNION ALL 成一条时间轴（[db-design §3.1]），不另建表。
 */
@Data
@TableName("gw_command_log")
public class GwCommandLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键，UNIQUE。见类注释。 */
    private String commandId;

    private String tenantId;

    /** 设备序列号。 */
    private String sn;

    private String cabinetNo;

    /** EJECT/LOCK/REBOOT/LOCATE/VOICE。 */
    private String type;

    /** 目标仓位；整柜类指令（REBOOT/LOCATE）为空。 */
    private Integer slotIndex;

    /** 指令载荷，JSON 原文。 */
    private String payload;

    /** PENDING/SENT/ACKED/TIMEOUT/FAILED。 */
    private String status;

    /** 重试次数。 */
    private Integer retry;

    /** 关联租借订单（弹出指令才有）。 */
    private String orderNo;

    /** 操作人（人工远控时的 employee_no；系统触发为空）。 */
    private String operator;

    /** 下发时刻。 */
    private String sentAt;

    /** 设备确认时刻；空 = 尚未确认。 */
    private String confirmedAt;

    private LocalDateTime createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;

    private LocalDateTime updatedAt;
}
