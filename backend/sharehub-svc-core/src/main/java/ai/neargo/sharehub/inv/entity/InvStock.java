package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 仓/区域库存结存（inv_stock，[db-design §3.3]）。
 *
 * <p>粒度 = {@code UK(warehouse_no, item_type, model)}：**没有业务键列**，
 * 三元组本身就是键 —— 结存是「某仓某型号有多少」的当前值，不需要对外单号。
 *
 * <p>{@code itemType=CABINET} 的结存对应机柜状态 {@code IN_STOCK}（[db-design §9A.2]）：
 * 「库存调拨」管的就是未投放机柜，没有 {@code IN_STOCK} 态就无法把仓库里的新机柜与已投放的区分开。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_stock")
public class InvStock extends BaseEntity {

    private String warehouseNo;

    /** CABINET/POWERBANK。 */
    private String itemType;

    /** 型号；空串 = 不分型号（DDL 默认 ''，不是 NULL —— 否则 UNIQUE 里 NULL 不互斥会放进重复行）。 */
    private String model;

    /** 结存数量。 */
    private Integer qty;
}
