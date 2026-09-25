package ai.neargo.sharehub.trade.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单主表实体（{@code ord_order}，ADR-018）。
 *
 * <p><b>类名暂留 {@code OrdOrder}</b>：表已改名为 `ord_order`，但类名改动会波及 7 个文件，
 * 与本次「拆表」的验证目标混在一起会让回归定位困难。类名重命名安排在下一步单独做
 * （纯机械改动，可独立验证）。**表名才是契约，类名只是称呼。**
 *
 * <p><b>充电宝专属列（{@code powerbankNo}/{@code returnCabinetNo}/{@code buyout}/
 * {@code durationMin}）已同步搬入 {@code ord_rent_ext}</b>，主表同名列暂留：
 * 删列不可回退，等所有读路径切到扩展表、验证一个版本周期后再 DROP。
 * 期间由 {@code RentOrderServiceImpl} 双写保证两处一致。
 *
 * <p><b>资金侧（分润/账务/发票/结算）只 join 本表，永不碰扩展表</b> ——
 * 这是「新增设备类型资金域一行不改」成立的唯一判据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_order")
public class OrdOrder extends BaseEntity {

    private String orderNo;

    /** 设备类型，一等维度。存量与新建的充电宝单为 {@code POWERBANK}。 */
    private String deviceType;

    private String cUserNo;
    private String cabinetNo;
    private String locationName;

    /*
     * —— 数据范围锚点（V9 加的列，实体一直没跟上）——
     *
     * 代理能看到哪些订单，靠 DataScopeHandler 按 agent_no / site_no 注入 WHERE。
     * **实体没有这三个字段 = 下单时写不进去**，于是新单对代理是隐形的 ——
     * 而页面照常渲染，只是少了行，不报错也不告警。
     *
     * 语义是「**下单时的快照**，不随设备后续调拨变动」（见 DataScopeRegistration
     * 里 ord_order 那段注释）—— 所以这三列只在 rent() 写一次，此后任何调拨都不回改。
     */
    private String locationNo;
    private String siteNo;
    private String agentNo;
    private String status;

    /** 类型子状态（DISPENSING/PREPARING/IDLE）。**资金侧不读**，只影响端上展示与超时兜底。 */
    private String subStatus;

    /** 槽位：充电宝=仓位，充电桩=枪，储物柜=格口。 */
    private Integer slotIndex;

    private String currency;

    /** 业务开始（充电宝=借出时刻，充电桩=开始充电）。共性列，取代类型各异的 {@code rentStartAt}。 */
    private java.time.LocalDateTime startedAt;

    /** 业务结束（充电宝=归还，充电桩=停止充电，储物柜=取出）。 */
    private java.time.LocalDateTime endedAt;

    /** 应收合计（共性）。与 {@link #feeAmount} 并存于过渡期，见类注释。 */
    private java.math.BigDecimal amount;

    private Double feeAmount;
    private Double depositAmount;

    /** 预授权单（逻辑引用 pay_auth）。充电桩必用，充电宝可选。 */
    private String authNo;

    /** 订单来源：APP/MINI/H5/INTERCONNECT。互联互通单据此区分。 */
    private String sourceChannel;

    /** 互联互通伙伴（intc_partner）。非互联互通单为 null。 */
    private String partnerNo;

    /** 命中的计价方案编号。**仅作留痕** —— 结算读的是快照，不回读方案。 */
    private String pricePlanNo;

    /** 使用的券号（逻辑引用 usr_coupon）。 */
    private String couponNo;

    /**
     * 券实际抵扣金额。
     *
     * <p>与 {@link #couponNo} 分开存：券号答「用了哪张」，这一列答「抵了多少」。
     * 后者事后推不出来 —— 模板面额会改，折扣券的抵扣额还取决于当时的应收。
     */
    private java.math.BigDecimal couponAmount;

    /** 免单原因（非空即免单）：INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF。 */
    private String freeReason;

    /** 减免额 = 免单时**本应收**的金额。直接置 0 会让「本月减免总额」拿不到数。 */
    private java.math.BigDecimal waivedAmount;

    /** 应收触及总封顶 → 买断（充电宝转 SOLD，[db-design §9A.1]）。 */
    private Integer buyout;

    /** 计价方案的**展开结构**快照（items+ladders+schedule 生效值），不是 plan_no 引用 —— 改价不影响在途单。 */
    private String priceSnapshot;

    // ——— 以下为充电宝专属，已搬入 ord_rent_ext，过渡期双写 ———
    private String returnCabinetNo;
    private String powerbankNo;
    private String rentStartAt;
    private String rentEndAt;
    private Integer durationMin;
}
