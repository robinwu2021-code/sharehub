package ai.neargo.opskit.process.api.statemachine;

/** 当前状态下没有这条迁移（或它是只能由门触发的系统迁移）。错误键与服务端一致。 */
public class IllegalTransitionException extends RuntimeException {

    public static final String ERROR_KEY = "opskit.process.illegal_transition";

    private final String from;
    private final String transition;

    public IllegalTransitionException(String from, String transition) {
        super("no transition '" + transition + "' from status '" + from + "'");
        this.from = from;
        this.transition = transition;
    }

    public String from() {
        return from;
    }

    public String transition() {
        return transition;
    }
}
