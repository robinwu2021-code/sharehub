package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("inv_count_review")
public class InvCountReview {
    @TableId private String countNo;
    private String warehouseNo;
    private Integer roundNo;
    private String executionState;
    private Integer contentVersion;
    private String contentHash;
    private String submitterNo;
    private String submitterName;
    private String scopeRegionId;
    private String approvalNo;
    private String instanceNo;
    private String decision;
    private String deciderNo;
    private String deciderName;
    private String decisionNote;
    private String lastError;
    private String gainInboundNo;
    private String lossOutboundNo;
}
