package ai.neargo.sharehub.dev.dto;

/**
 * dev 子域出参 VO / 入参命令。
 *
 * <p><b>约定</b>：新域一律用**域内 dto 文件**，不往顶层 {@code dto/Dto.java} 追加（已冻结）。
 * 字段镜像 ops-web {@code lib/types/device.ts}。
 */
public final class DevDtos {

    private DevDtos() {
    }

    /** 充电宝行，镜像前端 {@code Powerbank}（status 为 [db-design §9A.1] 7 态）。 */
    /** @param archivedAt 归档时间；`null` = 在用。运营端靠它把归档行置灰并显示归档时间。 */
    public record PowerbankRow(String powerbankNo, String sn, String vendorCode,
                               String cabinetNo, Integer slotIndex,
                               Integer battery, Integer cycles, String health, String status,
                               String archivedAt,
                               /* 疑似丢失标记时间（V113）；null = 未被怀疑。宝仍是 RENTED，这只是「待人核实」 */
                               String suspectedLostAt,
                               /* 入库质检结论（PENDING / PASSED / FAILED）；null = 存量免检 */
                               String qcStatus,
                               /* 所在仓；在库的宝才有。调拨按仓选宝要用它 */
                               String warehouseNo) {
    }

    /**
     * 充电宝写入命令。新建时 {@code powerbankNo} 由服务端取号（{@code PB} 前缀）。
     *
     * <p>更新时状态变更二选一：给 {@code event}（走状态机事件名，如 {@code SCRAP}/{@code OVERDUE}），
     * 或给目标 {@code status}（服务端反查事件后同样过状态机）。两者都不给则只改属性字段。
     */
    public record PowerbankCmd(String powerbankNo, String sn, String vendorCode,
                               String cabinetNo, Integer slotIndex,
                               Integer battery, Integer cycles, String health,
                               String status, String event) {
    }

    /** 仓位行，镜像前端 {@code Slot}。 */
    public record SlotRow(Integer slotIndex, String powerbankNo, Integer battery,
                          String lockStatus, String health) {
    }

    /** 实时监控行（读模型 {@code dev_cabinet ⋈ dev_shadow}），镜像前端 {@code CabinetMonitor}。 */
    public record CabinetMonitorRow(String cabinetNo, String locationName, Boolean online,
                                    String heartbeatAt, Integer signal, Integer temp,
                                    Integer faultCount) {
    }

    /** 设备日志行（读模型 {@code gw_command_log ∪ gw_message_log}），镜像前端 {@code DeviceLog}。 */
    public record DeviceLogRow(String logNo, String cabinetNo, String stream, String direction,
                               String eventType, String payload, String vendorCode,
                               String occurredAt, String result) {
    }

    /** 设备编码批次行，镜像前端 {@code DeviceCodeBatch}。 */
    public record CodeBatchRow(String batchNo, String vendorCode, String codeType,
                               String rangeStart, String rangeEnd, Integer total, Integer bound,
                               String producedAt, String status) {
    }

    /** OTA 固件版本行。 */
    public record OtaReleaseRow(String releaseNo, String fwType, String vendorCode, String version,
                                Integer versionCode, String artifactUrl, String checksum,
                                Boolean mandatory, String status, String releaseNotes) {
    }

    /** OTA 投放行，镜像前端 {@code OtaRollout}（另带 releaseNo/scope/targetRef）。 */
    public record OtaRolloutRow(String rolloutNo, String releaseNo, String fwVersion, String vendorCode,
                                String strategy, String scope, String targetRef,
                                Integer progress, String status, String createdAt) {
    }

    /** OTA 逐设备任务行（投放详情下钻）。 */
    public record OtaTaskRow(String taskNo, String rolloutNo, String cabinetNo, String status,
                            Integer progress, String previousVersion, String error) {
    }
}
