package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * OTA 逐设备升级任务（dev_ota_task，[db-design §3.1]）。一次投放 1─* 任务。
 *
 * <p><b>不继承 BaseEntity</b>：与 {@link DevOtaRollout} 同因，DDL 无乐观锁/软删列。
 */
@Data
@TableName("dev_ota_task")
public class DevOtaTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private String rolloutNo;

    private String cabinetNo;

    /** PENDING / DOWNLOADING / INSTALLING / SUCCESS / FAILED / ROLLED_BACK。 */
    private String status;

    /** 单机进度 0..100。 */
    private Integer progress;

    /** 升级前版本，回滚依据。 */
    private String previousVersion;

    private String error;

    private String createdAt;

    private String updatedAt;
}
