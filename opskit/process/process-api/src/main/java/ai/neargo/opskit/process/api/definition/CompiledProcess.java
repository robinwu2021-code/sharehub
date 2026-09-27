package ai.neargo.opskit.process.api.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编译后的流程：按「出发状态 → 迁移代号」建好索引（{@code *} 已展开）。只从校验通过的定义构造；
 * 版本不可变，所以按定义行缓存、永不失效（21/03 §四）。
 */
public final class CompiledProcess {

    private final long definitionId;
    private final String version;
    private final ProcessDef def;
    private final Map<String, StatusDef> statuses = new LinkedHashMap<>();
    private final Map<String, Map<String, TransitionDef>> byFrom = new HashMap<>();

    public CompiledProcess(long definitionId, String version, ProcessDef def) {
        this.definitionId = definitionId;
        this.version = version;
        this.def = def;
        def.statuses().forEach(s -> statuses.put(s.code(), s));
        for (TransitionDef t : def.transitions()) {
            for (String from : ProcessValidator.fromStatuses(t, statuses)) {
                byFrom.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(t.code(), t);
            }
        }
    }

    public long definitionId() {
        return definitionId;
    }

    public String version() {
        return version;
    }

    public ProcessDef definition() {
        return def;
    }

    public StatusDef initial() {
        return statuses.get(def.initialStatus());
    }

    public StatusDef status(String code) {
        StatusDef s = statuses.get(code);
        if (s == null) {
            throw new IllegalStateException("status " + code + " not in " + def.code() + "@" + version);
        }
        return s;
    }

    public Optional<TransitionDef> transition(String from, String code) {
        return Optional.ofNullable(byFrom.getOrDefault(from, Map.of()).get(code));
    }

    /** 从该状态出发、非系统的迁移 —— 界面按钮的候选集合（再按操作人过滤 who / when）。 */
    public List<TransitionDef> manualTransitionsFrom(String status) {
        List<TransitionDef> out = new ArrayList<>();
        byFrom.getOrDefault(status, Map.of()).values().stream().filter(t -> !t.system()).forEach(out::add);
        return out;
    }
}
