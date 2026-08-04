package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 设备影子快照（dev_shadow，[db-design §3.1]）。
 *
 * <p><b>不继承 BaseEntity</b>：影子的权威存储是 Redis，DB 只是兜底快照，
 * DDL 里没有 {@code version}/{@code deleted}（快照按 {@code cabinet_no} UK 覆盖写，不做乐观锁与软删）。
 *
 * <p><b>用途</b>：实时监控读模型 {@code dev_cabinet ⋈ dev_shadow}（[db-design §3.1 读模型]），不建表。
 */
@Data
@TableName("dev_shadow")
public class DevShadow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cabinetNo;

    /** 仓位快照 JSON 原文。 */
    private String slots;

    /** 在线标志（TINYINT(1)）。 */
    private Integer online;

    /** 信号强度 0..100。 */
    private Integer signal;

    /** 温度（摄氏度）。 */
    private Integer temp;

    /** 故障仓位数。 */
    private Integer faultCount;

    /** 快照时间。 */
    private String snapshotAt;
}
