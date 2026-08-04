package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * OTA 投放（dev_ota_rollout，[db-design §3.1]）。业务键前缀 {@code OTA}。
 *
 * <p><b>不继承 BaseEntity</b>：DDL 未给本表建 {@code version}/{@code deleted}/{@code tenant_id}
 * （投放单是一次性作业记录，作废走 {@code status=ROLLBACK} 而非软删）。
 */
@Data
@TableName("dev_ota_rollout")
public class DevOtaRollout {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String rolloutNo;

    /** 关联的固件版本 {@code dev_ota_release.release_no}。 */
    private String releaseNo;

    /** 冗余固件版本号，列表免联表。 */
    private String fwVersion;

    private String vendorCode;

    /** GRAY（灰度）/ FULL（全量）。 */
    private String strategy;

    /** DEVICE / LOCATION / ALL。 */
    private String scope;

    /** 投放目标引用：{@code scope=DEVICE} 时为 cabinetNo，{@code LOCATION} 时为 locationNo，{@code ALL} 时空。 */
    private String targetRef;

    /** 进度 0..100，由 {@code dev_ota_task} 汇总。 */
    private Integer progress;

    /** PENDING / RUNNING / DONE / ROLLBACK。 */
    private String status;

    private String createdAt;

    private String updatedAt;
}
