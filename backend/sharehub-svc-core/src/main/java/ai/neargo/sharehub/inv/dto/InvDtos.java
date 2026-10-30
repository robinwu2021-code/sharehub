package ai.neargo.sharehub.inv.dto;

import java.util.List;

/**
 * inv 子域出参 VO（[db-design §3.3] / [api/README §3.2 库存调拨]）。
 * 字段镜像 ops-web {@code lib/types/device.ts} 的 {@code InventoryTransfer}。
 */
public final class InvDtos {

    private InvDtos() {
    }

    /**
     * 调拨单行。
     *
     * <p>兼容前端现有 {@code InventoryTransfer}：{@code fromLocation}/{@code toLocation} 直接取
     * 快照名（{@code fromName}/{@code toName}），{@code operator} 取 {@code operatorNo}；
     * 同时把 v2 新增的 {@code fromType/fromRef/toType/toRef/itemType} 一并出参，
     * 前端可据此做「点名字跳到对应仓库/站点」——只给一个字符串是跳不过去的。
     */
    public record InventoryTransfer(String transferNo,
                                    String fromLocation, String toLocation,
                                    String fromType, String fromRef,
                                    String toType, String toRef,
                                    String itemType, Integer powerbankCount,
                                    String status, String operator, String createdAt) {
    }

    /**
     * 调拨单写入面（建单 / 更新共用）。
     *
     * <p><b>不含 {@code operatorNo}</b> —— 经办人由服务端按当前登录人回填。调拨是真的把设备
     * 从一个地方搬走，「谁经的手」是少了一台时唯一的追责线索，不能由请求体自称。
     * 同样不含 {@code transferNo}：建单时服务端取号，更新时以路径为准。
     *
     * <p>{@code status} 仅为旧客户端兼容保留。更新只能省略或保持当前状态；
     * 发货与签收必须调用专用动作端点，确保设备状态与库存账同时处理。
     */
    public record InvTransferReq(String fromType, String fromRef, String fromName,
                                 String toType, String toRef, String toName,
                                 String itemType, Integer powerbankCount, String status) {
    }

    /** 调拨单详情 = 单头 + 明细（收货核对逐件勾选用）。 */
    public record InventoryTransferDetail(InventoryTransfer transfer, List<TransferItem> items) {
    }

    /** 调拨明细行。{@code itemNo} 是充电宝号或机柜号（按单头 itemType 取其一）。 */
    public record TransferItem(String transferNo, String itemNo, boolean checked) {
    }

    /** 仓库行（供调拨选择调出/调入方）。 */
    public record Warehouse(String warehouseNo, String name, String regionId, String address) {
    }

    /** 库存结存行。粒度 = 仓 × 品类 × 型号。 */
    public record StockRow(String warehouseNo, String itemType, String model, Integer qty) {
    }
}
