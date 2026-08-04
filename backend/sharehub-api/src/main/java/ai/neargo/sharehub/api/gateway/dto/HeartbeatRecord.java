package ai.neargo.sharehub.api.gateway.dto;

/**
 * 设备心跳记录 —— device-gateway 暴露的遥测只读出参。
 *
 * <p>{@code metrics} 是原始指标 JSON，**不在此处解析** —— 指标的键随厂商与设备类型而异，
 * 网关只负责如实透出，解析归调用方或前端。
 */
public record HeartbeatRecord(String cabinetNo, String beatAt, String metrics) {
}
