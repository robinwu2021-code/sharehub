package ai.neargo.sharehub.trade.price;

import java.util.Arrays;
import java.util.Optional;

/**
 * 分时调价单的生命周期（{@code price_adjustment.status}，词表见 V40 的列注释）。
 *
 * <pre>
 *   SCHEDULED ──到点──▶ APPLIED ──到点──▶ REVERTED
 *       │                  │
 *    人工撤销            改价失败
 *       ▼                  ▼
 *   CANCELLED            FAILED
 * </pre>
 *
 * <h3>为什么收进枚举</h3>
 * 这套值散在十几处 {@code equals} / {@code setStatus} 里，而其中 {@link #CANCELLED}
 * 正好踩在本仓库已知的雷上：{@code CANCELLED} 与 {@code CANCELED} 两种拼法在 DDL 里都出现过
 * （见 {@code known-bare-status-literals.txt} 开头）。少一个 L，调度器就永远扫不到它 ——
 * 而扫不到的表现是「撤销了的调价仍然到点生效」，不报错。
 */
public enum PriceAdjustmentStatus {

    /** 已排期，等到 {@code effective_at} 由调度器生效。 */
    SCHEDULED,
    /** 已生效。到 {@code revert_at} 时会被还原。 */
    APPLIED,
    /** 生效前被人工撤销 —— 终态。 */
    CANCELLED,
    /** 已还原成原价 —— 终态。 */
    REVERTED,
    /** 生效或还原时出错 —— 终态，需要人介入。 */
    FAILED;

    public static Optional<PriceAdjustmentStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
