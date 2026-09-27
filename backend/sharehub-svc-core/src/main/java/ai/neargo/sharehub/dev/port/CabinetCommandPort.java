package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.dev.service.CabinetService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 交易域给机柜下指令的出口（trade → dev，同 {@link PowerbankTracker} 的定位）。
 *
 * <p><b>为什么要这个口子</b>：此前 {@code rent()} 的返回值里那个「指令号」是
 * {@code "CMD" + System.nanoTime()} —— 一个现造的字符串。也就是说**扫码借出从不向柜机
 * 发送任何指令**：订单直接置成 IN_USE，宝出不出来与系统无关。界面、日志、订单表都看不出来，
 * 因为整条链上没有一处会失败。
 *
 * <p>试借还（{@code TrialRentServiceImpl}）早就走的是真指令 + 回执推进，
 * 这里把借出接到同一条路上。
 */
@Component
public class CabinetCommandPort {

    private final CabinetService commands;

    public CabinetCommandPort(CabinetService commands) {
        this.commands = commands;
    }

    /**
     * 弹仓。
     *
     * @param slotIndex 指定仓位；null = 由柜机自己挑一仓（没有宝台账的柜子走这条）
     * @param bizRef    业务引用（订单号）。它同时是**指令幂等键**的一半
     *                  （{@code <bizRef>:EJECT}）—— 同一单重发不会弹出两块宝，
     *                  与试借还同一约定
     * @return 网关指令号，落在订单回执里供排障对账
     */
    public String eject(String cabinetNo, Integer slotIndex, String bizRef) {
        Map<String, Object> params = new HashMap<>();
        if (slotIndex != null) params.put("slotIndex", slotIndex);
        params.put("commandId", bizRef + ":EJECT");
        params.put("bizType", "RENT");
        params.put("bizRef", bizRef);
        return commands.sendCommand(cabinetNo, "EJECT", params).commandId();
    }
}
