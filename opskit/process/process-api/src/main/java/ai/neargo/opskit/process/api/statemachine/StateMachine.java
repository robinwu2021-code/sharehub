package ai.neargo.opskit.process.api.statemachine;

import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.ProcessValidator;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯 Java 状态机（21/07 O2-2）：与服务端同一套流程定义模型，业务进程内直接用、不需要服务在线。
 * 适用于「只要合法迁移校验」的场景（需求 SM-01 ~ SM-11），如业务系统里原先手写的状态机。
 *
 * <p>只看状态与迁移；门、who、when、动作属于服务端引擎，这里不执行。系统迁移（只能由门触发）一律视为不可走。
 */
public final class StateMachine {

    private final ProcessDef def;
    private final CompiledProcess compiled;

    private StateMachine(ProcessDef def) {
        this.def = def;
        this.compiled = new CompiledProcess(0, "local", def);
    }

    /** 校验不通过直接抛 {@link GraphDefinitionException}（带全部错误），让配错的状态机在启动时就暴露。 */
    public static StateMachine of(ProcessDef def) {
        var errors = ProcessValidator.validate(def);
        if (!errors.isEmpty()) {
            throw new GraphDefinitionException(def.code(), errors);
        }
        return new StateMachine(def);
    }

    public String initial() {
        return def.initialStatus();
    }

    /** @return 目标状态；非法迁移抛 {@link IllegalTransitionException} */
    public String next(String from, String transition) {
        TransitionDef t = compiled.transition(from, transition).filter(x -> !x.system())
                .orElseThrow(() -> new IllegalTransitionException(from, transition));
        return t.to();
    }

    public boolean canFire(String from, String transition) {
        return compiled.transition(from, transition).filter(x -> !x.system()).isPresent();
    }

    /** 当前状态可走的迁移代号（界面亮哪些按钮；不含系统迁移）。 */
    public List<String> transitionsFrom(String status) {
        compiled.status(status);
        return compiled.manualTransitionsFrom(status).stream().map(TransitionDef::code).toList();
    }

    /**
     * 由起止状态反查迁移（兼容 {@code assertTransit(from, to)} 这类旧调用点）。
     * 没有 → {@link IllegalTransitionException}；多于一条 → {@link AmbiguousEventException}。
     */
    public String transitionFor(String from, String to) {
        List<String> hits = compiled.manualTransitionsFrom(from).stream().filter(t -> t.to().equals(to)).map(TransitionDef::code).toList();
        if (hits.isEmpty()) {
            throw new IllegalTransitionException(from, "->" + to);
        }
        if (hits.size() > 1) {
            throw new AmbiguousEventException(from, to, hits);
        }
        return hits.get(0);
    }

    public StatusCategory categoryOf(String status) {
        return compiled.status(status).category();
    }

    /** 终态由类别推导（DONE / CANCELLED），不许手写（需求 SM-06）。 */
    public boolean isTerminal(String status) {
        return categoryOf(status).isTerminal();
    }

    /** 投影为「起 → 止集合」：兼容只认纯图的旧调用点（需求 SM-10）。 */
    public Map<String, Set<String>> projection() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (StatusDef s : def.statuses()) {
            Set<String> tos = new LinkedHashSet<>();
            compiled.manualTransitionsFrom(s.code()).forEach(t -> tos.add(t.to()));
            out.put(s.code(), Set.copyOf(tos));
        }
        return out;
    }

    /** 导出定义（登记到服务、或给前后端一致卡口读，需求 SM-09）。 */
    public ProcessDef definition() {
        return def;
    }
}
