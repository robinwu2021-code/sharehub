package ai.neargo.sharehub.inv;

/**
 * 物料单据用途（{@code inv_material_doc.purpose}，V210）。与库存服务业主画像里的用途码一一对应
 * （backend/scripts/inventory-local/onboard.sh 的 PROFILE）：调拨在库存服务侧是 TRANSFER_OUT + TRANSFER_IN 两张单。
 */
public enum MaterialDocPurpose {

    PURCHASE(MaterialDocKind.INBOUND),
    OPENING(MaterialDocKind.INBOUND),
    RETURN(MaterialDocKind.INBOUND),
    REQUISITION(MaterialDocKind.OUTBOUND),
    SCRAP(MaterialDocKind.OUTBOUND),
    RETURN_SUPPLIER(MaterialDocKind.OUTBOUND),
    TRANSFER(MaterialDocKind.TRANSFER);

    public final MaterialDocKind kind;

    MaterialDocPurpose(MaterialDocKind kind) {
        this.kind = kind;
    }
}
