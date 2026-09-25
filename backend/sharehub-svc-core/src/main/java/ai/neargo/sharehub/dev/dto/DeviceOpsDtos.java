package ai.neargo.sharehub.dev.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 机柜运维（状态动作 / 保护动作 / 试借还 / 设备信号）的请求与响应（TDD-运营核心流程/04）。 */
public final class DeviceOpsDtos {

    private DeviceOpsDtos() {
    }

    /** 标故障 / 撤机 / 退役共用：原因必填。 */
    /**
     * 入库质检（C3）。机柜看 powerOn / slotsOk，充电宝看 battery / cycles；
     * result 可空 = 按检查项判定；显式 FAILED 须带 note，显式 PASSED 但检查项不过 → 400。
     */
    public record QcReq(Boolean powerOn, Boolean slotsOk, Integer battery, Integer cycles, String result, String note) {
    }

    public record QcRecord(String qcNo, String itemType, String itemNo, Boolean powerOn, Boolean slotsOk, Integer battery,
                           Integer cycles, String result, String note, String inspectedBy, LocalDateTime inspectedAt) {
    }

    public record ReasonReq(String reason) {
    }

    public record ProtectionReq(String action, Integer slotIndex, String reason) {
    }

    public record Protection(String protectionNo, String cabinetNo, Integer slotIndex, String action, String holderType,
                             String holderRef, String reason, boolean active, LocalDateTime createdAt,
                             LocalDateTime releasedAt, String releaseReason) {
    }

    public record TrialRent(String trialNo, String cabinetNo, Integer slotIndex, String powerbankNo, String status,
                            LocalDateTime ejectedAt, LocalDateTime returnedAt, String failReason, String operator,
                            LocalDateTime createdAt) {
    }

    /**
     * 网关推送的设备事件批（{@code POST /internal/events/device}）。
     *
     * @param type 归一化信号码（dev_event_code.code）；未知码记 WARN 后按无保护动作处理
     */
    public record DeviceEvent(String eventId, String type, String deviceNo, Integer slotIndex, String powerbankNo,
                              String vendorCode, String vendorErrorCode, LocalDateTime occurredAt,
                              Map<String, String> attrs) {
    }

    public record DeviceEventBatch(List<DeviceEvent> events) {
    }

    /** 信号字典行（告警代码页「设备信号」分段）。 */
    public record SignalCode(String code, String name, String nameEn, String category, String scope, String protectiveAction,
                             String clearsCode, String feeds) {
    }

    /** @param accepted 本批真正处理的条数（重复 eventId 跳过不计） */
    public record IngestResult(int accepted, int duplicated, int unknownCode) {
    }
}
