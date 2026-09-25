package ai.neargo.sharehub.inv;

/** 调拨两端的类型（{@code inv_transfer.from_type / to_type}）：决定 from_ref / to_ref 指向哪张表。 */
public enum TransferEndpointType {
    WAREHOUSE, SITE, LOCATION
}
