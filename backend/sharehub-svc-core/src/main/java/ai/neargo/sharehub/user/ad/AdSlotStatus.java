package ai.neargo.sharehub.user.ad;

import java.util.Arrays;
import java.util.Optional;

/**
 * 广告位占用与否（{@code ad_slot.status}，词表见 V65 的列注释 {@code IDLE/OCCUPIED}）。
 *
 * <h3>这一列的默认值曾经不在词表里</h3>
 * 建表时给的是 {@code DEFAULT 'ACTIVE'}，而服务建广告位写 {@code IDLE}、
 * 运营端 {@code AdSlot.status} 只有 {@code IDLE/OCCUPIED}。
 * 不走服务的插入路径落进去的是一个两端都不认识的值 —— 详见 {@code NotifyTemplateStatus}
 * 的同类说明与 V65。
 */
public enum AdSlotStatus {

    /** 空闲，可投放。 */
    IDLE,
    /** 已被某个投放占用。 */
    OCCUPIED;

    public static Optional<AdSlotStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
