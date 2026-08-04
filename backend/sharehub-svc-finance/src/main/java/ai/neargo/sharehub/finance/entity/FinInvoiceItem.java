package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 发票关联订单（fin_invoice_item，[db-design §1.7]）。
 *
 * <p>存在的唯一理由：把前端的 {@code Invoice.orderNos} 多值串拆成行，
 * DDL 上有 {@code UK(invoice_no, order_no)} 兜底「同一订单不得开两次票」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_invoice_item")
public class FinInvoiceItem extends BaseEntity {

    private String invoiceNo;

    private String orderNo;
}
