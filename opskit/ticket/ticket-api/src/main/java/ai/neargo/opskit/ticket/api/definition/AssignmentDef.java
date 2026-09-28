package ai.neargo.opskit.ticket.api.definition;

/**
 * 派单策略（21/02 §7.1）。
 *
 * @param group              QUEUE / ROUND_ROBIN / LEAST_LOADED 用的组
 * @param field              FIELD 用的成员字段代号
 * @param externalTimeoutSec EXTERNAL 等待外部回填的超时
 * @param fallback           EXTERNAL 超时后的兜底策略（不能再是 EXTERNAL）
 */
public record AssignmentDef(Strategy strategy, String group, String field, Integer externalTimeoutSec, Strategy fallback) {

    public enum Strategy { MANUAL, QUEUE, ROUND_ROBIN, LEAST_LOADED, FIELD, EXTERNAL }

    public static final AssignmentDef MANUAL = new AssignmentDef(Strategy.MANUAL, null, null, null, null);
}
