package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 机柜实体（dev_cabinet）。镜像 Cabinet；仓位由 Service 派生；主键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_cabinet")
public class DevCabinet extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {
    private String cabinetNo;
    private String sn;
    private String vendorCode;

    /** 设备类型（ADR-018 一等维度）：POWERBANK / EV_PILE / LOCKER。容器本身设备无关，靠这列区分。 */
    private String deviceType;
    private String model;
    private String locationNo;
    /** 归属站点（冗余·随点位级联，数据范围锚点）。 */
    private String siteNo;
    /** 归属代理（冗余·随点位/站点级联，数据范围锚点；空=平台直营）。 */
    private String agentNo;
    private String locationName;
    private Integer slotTotal;
    private Integer availableCount;
    private String onlineStatus;
    private String status;
    private String fwVersion;
    private String lastHeartbeatAt;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;

    // —— 2026-09-25 机柜状态机 / 上线门禁（V97）——
    /** 最近一次上线时刻。 */
    private java.time.LocalDateTime wentLiveAt;
    /** 最近一次试借还通过时刻；早于 {@link #boundAt} 即失效（换点位后需重测）。 */
    private java.time.LocalDateTime trialPassedAt;
    /** 最近一次绑定点位时刻。 */
    private java.time.LocalDateTime boundAt;
    private String faultReason;
    private java.time.LocalDateTime retiredAt;

    // —— 批次 C（V107）——
    /** 入库质检：PENDING / PASSED / FAILED；null = 质检上线前的存量，免检（{@link ai.neargo.sharehub.dev.QcStatus}）。 */
    private String qcStatus;
    /** 在库时所在仓（调拨签收回写）。 */
    private String warehouseNo;
}
