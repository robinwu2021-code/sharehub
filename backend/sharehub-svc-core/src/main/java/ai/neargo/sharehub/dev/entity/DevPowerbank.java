package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 充电宝实体（dev_powerbank，[db-design §3.1]）。
 *
 * <p><b>状态机</b>：{@code status} 为 [db-design §9A.1] 定稿的 7 态
 * {@code IN_STOCK/IN_CABINET/RENTED/FAULT/LOST/SOLD/SCRAP}，迁移一律经
 * {@link ai.neargo.sharehub.dev.PowerbankStateMachine}。
 *
 * <p><b>位置不进状态位</b>：充电宝「在哪」由 {@code cabinetNo + slotIndex} 精确表达，
 * 状态机只管资产生命周期（§9A.1 的核心判断），二者不得互相推导。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_powerbank")
public class DevPowerbank extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    private String powerbankNo;

    private String sn;

    private String vendorCode;

    /** 电量 0..100。 */
    private Integer battery;

    /** 循环次数（健康度依据），v2-alter 补列。 */
    private Integer cycles;

    /** OK / FAULT，v2-alter 补列。 */
    private String health;

    /** 生命周期状态，7 态见类注释。 */
    private String status;

    /** 当前所在机柜；{@code IN_STOCK}/{@code SOLD}/{@code SCRAP} 时为空。 */
    private String cabinetNo;

    private Integer slotIndex;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;

    // —— 批次 C（V107）——
    /** 入库质检：PENDING / PASSED / FAILED；null = 存量免检。 */
    private String qcStatus;
    private String warehouseNo;
}
