package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 分成基数：实收（扣退款）/ 应收。 */
public enum ContractShareBase {
    NET, GROSS;

    public static ContractShareBase of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分成基数非法: " + v));
    }
}
