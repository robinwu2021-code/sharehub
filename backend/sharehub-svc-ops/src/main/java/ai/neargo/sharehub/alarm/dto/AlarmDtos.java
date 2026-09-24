package ai.neargo.sharehub.alarm.dto;

/**
 * dev/alarm 子域出参 VO（[db-design §3.2] / [api/README §3.3]）。
 *
 * <p>字段镜像 ops-web {@code lib/types/alarm.ts}（{@code AlarmRecord} / {@code AlarmNotice} /
 * {@code AlarmCode} / {@code AlarmRule}）。按域内 dto 文件放置，不往冻结的顶层 {@code dto/Dto.java} 追加。
 *
 * <p>布尔在库里是 {@code TINYINT(1)}（实体用 {@code Integer}），到 VO 转回 {@code boolean}
 * —— 前端 {@code AlarmCode.autoWorkOrder} 是 boolean，让转换只发生在一处。
 */
public final class AlarmDtos {

    private AlarmDtos() {
    }

    /**
     * 告警记录行，镜像前端 {@code AlarmRecord}。
     *
     * <p>{@code alarmCode}(平台统一码) 与 {@code vendorErrorCode}(厂商原始码) 同时出参 ——
     * 列表页要能同时按统一码聚合、按原始码对厂商追问。
     * {@code workOrderNo} 对应实体 {@code woNo}（前端命名保留 {@code workOrderNo}）。
     */
    public record AlarmRecord(String alarmNo, String cabinetNo, String siteNo, String siteName,
                              String agentNo, String vendorCode, String alarmCode, String vendorErrorCode,
                              String level, String source, String occurredAt, String status,
                              String workOrderNo, String remark, String dedupKey, Integer count) {
    }

    /** 告警通知流水行，镜像前端 {@code AlarmNotice}。 */
    /** 告警通知行，镜像前端 {@code AlarmNotice}（含重发链路 idempotencyKey/resendOf，V31）。 */
    public record AlarmNotice(String noticeNo, String alarmNo, String channel, String target,
                              String sentAt, String status, String failReason,
                              String idempotencyKey, String resendOf) {
    }

    /** 告警代码字典行，镜像前端 {@code AlarmCode}（含建议处置 + 自动开单开关）。 */
    /** {@code archivedAt}：归档时间，`null` = 在用。运营端靠它把归档行置灰并显示归档时间。 */
    public record AlarmCode(String code, String message, String messageEn, String messageAr,
                            String level, String suggestion, boolean autoWorkOrder,
                            String archivedAt) {
    }

    /** 通知规则行，镜像前端 {@code AlarmRule}（含静默窗口 + 升级策略）。 */
    /** {@code archivedAt}：归档时间，`null` = 在用。运营端靠它把归档行置灰并显示归档时间。 */
    public record AlarmRule(String ruleNo, String alarmCode, String target, String channel,
                            String method, String quietStart, String quietEnd,
                            Integer escalateMinutes, String status, String archivedAt) {
    }

    /**
     * 转工单结果。
     *
     * <p>{@code created=false} 表示**命中幂等**：该告警此前已开过单，返回的是首次生成的 {@code woNo}，
     * 没有产生第二张单（[api/README §3.3 转工单幂等]）。前端据此区分「已为你打开既有工单」与「新建成功」。
     */
    public record WorkOrderRef(String alarmNo, String woNo, boolean created) {
    }

    /** 确认告警结果（回带落库后的状态，避免前端自己猜）。 */
    public record AckResult(String alarmNo, String status) {
    }
}
