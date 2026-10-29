package ai.neargo.sharehub.inv.dto;

/** 供应商（V201）。 */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record Supplier(String supplierNo, String name, String vendorCode, String contactName, String phone, String email, String address,
                           String note, String archivedAt) {
    }

    /**
     * 供应商绩效（V207）：到货 = 带该供应商的入库单明细台数；入库 = 已入库；不合格 = 质检判不合格；
     * 退货 = 处置出库退给它的台数。合格率 = (到货 − 不合格) / 到货，没到过货为 null。
     */
    public record SupplierStats(String supplierNo, long received, long inbound, long failed, long returned, Double passRate) {
    }

    /** 新建 / 编辑：供应商号新建时系统生成。 */
    public record SupplierReq(String name, String vendorCode, String contactName, String phone, String email, String address, String note) {
    }
}
