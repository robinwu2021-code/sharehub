package ai.neargo.sharehub.loc.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 门店生命周期流转留痕（loc_site_lifecycle_log，[db-design §3.4]）。
 *
 * <p><b>append 表</b>：只插不改不删，DDL 无 {@code version}/{@code deleted}，
 * 故**不继承 BaseEntity**（[骨架规约 §4]）。{@code loc_site_lifecycle} 的每一次阶段变更
 * 都必须在此落一行，否则「为什么这个站退场了」无从回溯。
 */
@Data
@TableName("loc_site_lifecycle_log")
public class LocSiteLifecycleLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String siteNo;

    /** 迁出阶段；空 = 首次建档。 */
    private String fromStage;

    /** 迁入阶段。 */
    private String toStage;

    /** 操作人 employee_no。 */
    private String operator;

    private String reason;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
