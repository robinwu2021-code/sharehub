package ai.neargo.opskit.process.api.statemachine;

import java.util.List;

/** 由起止状态反查迁移时不唯一（如 WITHDRAW 与 MERGE 都 → CLOSED）：宁可报错，也不随便挑一个（需求 SM-05）。 */
public class AmbiguousEventException extends RuntimeException {

    private final List<String> candidates;

    public AmbiguousEventException(String from, String to, List<String> candidates) {
        super("more than one transition " + from + " -> " + to + ": " + candidates);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> candidates() {
        return candidates;
    }
}
