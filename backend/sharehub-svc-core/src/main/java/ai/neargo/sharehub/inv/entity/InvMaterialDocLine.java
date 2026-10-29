package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 物料单据明细（V210）。serialNos 逗号分隔。 */
@Data
@TableName("inv_material_doc_line")
public class InvMaterialDocLine {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Integer lineNo;
    private String materialNo;
    private String materialName;
    private Integer qty;
    private String serialNos;
    private BigDecimal unitCost;
    private LocalDateTime createdAt;
}
