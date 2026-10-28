package ai.neargo.sharehub.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 转账文件导出记录（stl_payout_export，V158，append）：文件含完整 IBAN，谁导出过要查得到。 */
@Data
@TableName("stl_payout_export")
public class StlPayoutExport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String format;
    private String fileSha256;
    private String exportedBy;
    private String exportedName;
    private LocalDateTime exportedAt;
    private String tenantId;
}
