package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * C 端发票抬头（usr_invoice_title，[db-design §6.6]，C-IV-02）。
 * {@code type=COMPANY} 时 {@code vatTrn}（税号/TRN）必填 —— MENA 增值税发票的硬要求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_invoice_title")
public class UsrInvoiceTitle extends BaseEntity {

    private String titleNo;

    private String cUserNo;

    /** PERSONAL / COMPANY。 */
    private String type;

    private String title;

    /** 税号 TRN（COMPANY 必填）。 */
    private String vatTrn;

    /** TINYINT(1)：同一用户至多一条为 1。 */
    private Integer isDefault;
}
