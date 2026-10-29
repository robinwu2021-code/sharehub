package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处置出库明细（V203）。 */
@Data
@TableName("inv_disposal_item")
public class InvDisposalItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String disposalNo;
    private String itemType;
    private String itemNo;
    private LocalDateTime createdAt;
}
