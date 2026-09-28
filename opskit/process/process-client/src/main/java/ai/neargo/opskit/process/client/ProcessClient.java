package ai.neargo.opskit.process.client;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.SlaMetric;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 流程能力的客户端（21/05 §二「只经客户端接口」）。进程内实现在 process-core（{@code EmbeddedProcessClient}），
 * 调用方的事务会被加入（同进程同事务）；拆分部署后换 HTTP 实现，语义不变。
 */
public interface ProcessClient {

    InstanceView start(Actor actor, StartRequest req);

    InstanceView transition(Actor actor, String instanceNo, String code, TransitionRequest req);

    InstanceView get(Actor actor, String instanceNo);

    /** 按租户读实例（不做操作人可见性判断）：供上层同步快照；不存在为空。 */
    Optional<InstanceView> snapshot(TenantContext t, String instanceNo);

    InstanceView updateFields(Actor actor, String instanceNo, Map<String, Object> patch);

    void replaceScopes(Actor actor, String instanceNo, Collection<String> tags);

    /** 流程定义的当前版本（空间优先，其次账户模板）；不存在为空。 */
    Optional<ProcessDef> processDef(TenantContext t, String processCode);

    boolean slaPolicyExists(TenantContext t, String policyCode);

    List<SlaMetric> slaMetrics(TenantContext t, String instanceNo);

    /** 实例历史（可见性由调用方先判，这里按应用自身读）。 */
    List<HistoryEntry> history(TenantContext t, String instanceNo);
}
