package ai.neargo.sharehub.inv.dto;

/** 供应商（V201）。 */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record Supplier(String supplierNo, String name, String contactName, String phone, String email, String address,
                           String note, String archivedAt) {
    }

    /** 新建 / 编辑：供应商号新建时系统生成。 */
    public record SupplierReq(String name, String contactName, String phone, String email, String address, String note) {
    }
}
