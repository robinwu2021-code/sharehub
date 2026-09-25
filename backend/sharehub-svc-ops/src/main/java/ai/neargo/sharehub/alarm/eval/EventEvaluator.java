package ai.neargo.sharehub.alarm.eval;

import java.util.List;

/** 事件型判定器（EVENT）：一个事件即一个（或零个）条件成立。 */
public interface EventEvaluator<E> {

    Class<E> eventType();

    List<Finding> onEvent(E event);
}
