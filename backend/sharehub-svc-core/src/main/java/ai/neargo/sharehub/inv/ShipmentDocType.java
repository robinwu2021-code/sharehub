package ai.neargo.sharehub.inv;

/** 发货物流挂在哪种单据上（{@code inv_shipment.doc_type}，V205）。 */
public enum ShipmentDocType {

    /** 调拨发出：doc_ref = 调拨单号。 */
    TRANSFER,
    /** 出库给商家：doc_ref = 商家号。 */
    ISSUE,
    /** 退供应商：doc_ref = 处置单号。 */
    DISPOSAL
}
