package ai.neargo.sharehub.user.ad.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 广告位（ad_slot，[db-design §6.4]）—— 挂在机柜上的可售广告资源。
 *
 * <p>广告位归 ops 前缀（{@code /api/ops/ad-slots}）而其余广告能力归 {@code /api/user}，
 * 原因是广告位本质是**设备资产的一个面**（挂 {@code cabinetNo}），运维在登记机柜时一并登记。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ad_slot")
public class AdSlot extends BaseEntity {

    private String slotNo;

    /** 所属机柜 dev_cabinet.cabinet_no（逻辑引用，无物理 FK）。 */
    private String cabinetNo;

    /** SCREEN（屏幕投屏）/ BODY（柜体贴附）。 */
    private String position;

    /** 尺寸描述，如 {@code 1080x1920} 或 {@code A4}。 */
    private String size;

    /** IDLE（空闲可售）/ OCCUPIED（已被排期占用）。 */
    private String status;
}
