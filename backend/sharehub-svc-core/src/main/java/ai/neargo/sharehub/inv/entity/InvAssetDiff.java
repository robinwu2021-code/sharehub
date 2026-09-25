package ai.neargo.sharehub.inv.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资产差异（inv_asset_diff，V107）：调拨签收缺件 / 多件、撤机清点数量不符。 */
@Data
@TableName("inv_asset_diff")
public class InvAssetDiff {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String diffNo;
    private String tenantId;
    /** TRANSFER / REMOVAL。 */
    private String sourceType;
    private String sourceRef;
    /** {@link ai.neargo.sharehub.inv.AssetDiffKind}。 */
    private String kind;
    private String itemType;
    private String itemNo;
    private String siteNo;
    private String cabinetNo;
    private Integer expectedQty;
    private Integer actualQty;
    /** {@link ai.neargo.sharehub.inv.AssetDiffStatus}。 */
    private String status;
    private String resolveNote;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
