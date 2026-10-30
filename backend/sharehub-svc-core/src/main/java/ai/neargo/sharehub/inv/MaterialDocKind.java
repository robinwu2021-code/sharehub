package ai.neargo.sharehub.inv;

/** 物料单据大类（{@code inv_material_doc.kind}，V210）。 */
public enum MaterialDocKind {
    INBOUND,
    OUTBOUND,
    /** 调拨 = 库存服务一出一入两张单。 */
    TRANSFER
}
