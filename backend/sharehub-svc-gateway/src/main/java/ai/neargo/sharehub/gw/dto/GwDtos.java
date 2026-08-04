package ai.neargo.sharehub.gw.dto;

/**
 * gw 子域出参 VO（[db-design §四] / [api/README §3.2]）。
 * 字段镜像 ops-web {@code lib/types/device.ts} 的 {@code CommandRecord}。
 */
public final class GwDtos {

    private GwDtos() {
    }

    /**
     * 指令下发记录行，镜像前端 {@code CommandRecord}。
     *
     * <p>比前端多出 {@code sn}/{@code retry}/{@code sentAt}/{@code confirmedAt}/{@code orderNo}：
     * 排障时「发了几次、什么时候发的、设备什么时候认的、对应哪笔订单」缺一个就得去查日志，
     * 这些列本来就在表上，出参不给才是多此一举。
     */
    public record CommandRecord(String commandId, String sn, String cabinetNo, String type,
                                Integer slotIndex, String status, Integer retry,
                                String orderNo, String operator,
                                String sentAt, String confirmedAt, String createdAt) {
    }

    /** 供应商行，镜像前端 {@code Vendor}（system.ts）。 */
    public record VendorVO(String vendorCode, String name, String accessMode,
                           String status, String apiBase, Integer deviceCount) {
    }

    /** 连通性探测结论，镜像前端 {@code VendorProbeResult}（system.ts）。 */
    public record VendorProbeResult(String vendorCode, Boolean ok, String endpoint,
                                    Long latencyMs, String checkedAt, String message, String detail) {
    }
}
