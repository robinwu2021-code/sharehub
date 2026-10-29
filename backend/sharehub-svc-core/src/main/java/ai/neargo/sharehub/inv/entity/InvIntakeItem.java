package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 到仓待检明细（inv_intake_item，V192）。一台柜 / 一块宝一行。 */
@Data
@TableName("inv_intake_item")
public class InvIntakeItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String intakeNo;
    /** CABINET / POWERBANK。 */
    private String itemType;
    private String itemNo;
    /** 最近一次质检记录号 = 入库放行凭证。 */
    private String qcNo;
    /** PENDING / PASSED / FAILED。 */
    private String qcResult;
    /** PENDING / INBOUND / RETURN_SUPPLIER / SCRAP。 */
    private String disposition;
    private String dispositionNote;
    private String handledBy;
    private LocalDateTime handledAt;
}
