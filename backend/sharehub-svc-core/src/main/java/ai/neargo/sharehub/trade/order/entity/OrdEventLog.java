package ai.neargo.sharehub.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订单状态流水（{@code ord_event_log}，[db-design §5.1]）——**append 表**，承载订单详情的状态时间线。
 *
 * <p><b>为什么不继承 {@code BaseEntity}</b>：DDL 里本表没有 {@code version}/{@code deleted}
 * （也没有 {@code tenant_id}）——流水只追加、不改不删，乐观锁与软删都无意义，
 * 硬套基类会让 MyBatis-Plus 往不存在的列上写 SQL。故自带 {@code @TableId(AUTO)}。
 *
 * <p>写入方是各状态流转点（借出/归还/干预/异常处置…），读出方是订单详情页时间线。
 */
@Data
@TableName("ord_event_log")
public class OrdEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** 迁移前状态；首条事件可为空。 */
    private String fromStatus;

    private String toStatus;

    /** 触发事件（RENT/RETURN/SETTLE/INTERVENE/EXCEPTION_HANDLE…）。 */
    private String event;

    /** 操作人（[db-design §5.1] 关键列含 operator；DDL 尚缺此列，见交付报告）。 */
    private String operator;

    /** 事件附带的结构化数据（JSON 文本原样存取，不在骨架阶段建模）。 */
    private String data;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
