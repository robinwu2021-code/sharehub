package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 分成模式：纯分成 / 固定进场费 / 保底加分成 / 免费入驻。 */
public enum ContractShareMode {
    SHARE, ENTRY_FEE, GUARANTEE, FREE;

    public static ContractShareMode of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分成模式非法: " + v));
    }
}
