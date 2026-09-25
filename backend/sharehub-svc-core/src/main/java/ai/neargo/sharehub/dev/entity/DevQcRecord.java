package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 入库质检记录（dev_qc_record，追加表，V107）。 */
@Data
@TableName("dev_qc_record")
public class DevQcRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String qcNo;
    private String tenantId;
    /** CABINET / POWERBANK。 */
    private String itemType;
    private String itemNo;
    private Boolean powerOn;
    private Boolean slotsOk;
    private Integer battery;
    private Integer cycles;
    /** PASSED / FAILED。 */
    private String result;
    private String note;
    private String inspectedBy;
    private LocalDateTime inspectedAt;
    private LocalDateTime createdAt;
}
