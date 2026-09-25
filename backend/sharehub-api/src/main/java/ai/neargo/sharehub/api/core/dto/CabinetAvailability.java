package ai.neargo.sharehub.api.core.dto;

import java.time.LocalDateTime;

/**
 * 一台机柜此刻「能不能借、能不能还」（业务告警判定的输入）。
 *
 * @param online          最近心跳 ≤ 3 分钟（设备不会主动报离线，离线全靠心跳时间推断）
 * @param rentBlocked     有生效中的整柜停借保护
 * @param rentableSlots   可借的宝数（在柜、电量达标、所在仓未被禁用 / 锁定）
 * @param returnableSlots 可还的空仓数（空仓且未被禁用 / 锁定）
 * @param inFlightOrders  从该柜借出、尚未归还的订单数（可还域影响加成）
 */
public record CabinetAvailability(String cabinetNo, String siteNo, String agentNo, String status, boolean online,
                                  LocalDateTime lastHeartbeatAt, boolean rentBlocked, int slotTotal,
                                  int rentableSlots, int returnableSlots, int inFlightOrders) {
}
