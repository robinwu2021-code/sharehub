package ai.neargo.sharehub.alarm;

/**
 * 告警关闭原因（{@code dev_alarm.close_reason}）—— 关闭时必填。
 *
 * <p><b>为什么必填</b>：与「验收关单必须给结论」同一口径。
 * 更实际的理由是 <b>「误报率」这个数只有在关闭时记了原因才算得出来</b> ——
 * 不记就只知道「这个月关了 300 条」，不知道其中多少是设备真故障、
 * 多少是规则太敏感。规则调不动，告警就会一直吵，吵到没人看。
 *
 * <p>与运营端 {@code ops-web/lib/types/alarm.ts} 的 {@code AlarmCloseReason} 一字不差
 * （{@code StatusVocabularyAcrossEndsTest} 钉住）。
 */
public enum AlarmCloseReason {

    /** 设备侧问题已处理（通常伴随一张完工的工单）。 */
    RESOLVED,
    /** 误报：设备其实没问题，是规则或阈值太敏感 —— 这一档是调规则的依据。 */
    FALSE_ALARM,
    /** 自愈：再次上报时已恢复，无需人工处理。 */
    SELF_HEALED,
    /** 系统自愈动作成功（撤销未出宝订单、按宝入柜时刻结单）—— 只由判定引擎写。 */
    AUTO_FIXED,
    /** 被上层告警取代（柜级并入站点级）—— 只由判定引擎写。 */
    SUPERSEDED;

    /** 人工关闭可选的档位：系统档不给人选，否则「自愈率」这类指标会被手工值污染。 */
    public boolean manual() {
        return this == RESOLVED || this == FALSE_ALARM || this == SELF_HEALED;
    }

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AlarmCloseReason of(String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("关闭原因必填（RESOLVED/FALSE_ALARM/SELF_HEALED）");
        }
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("关闭原因非法: " + v
                    + "（仅 RESOLVED/FALSE_ALARM/SELF_HEALED）");
        }
    }
}
