package ai.neargo.opskit.process.core.embedded;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.SlaMetric;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.process.core.app.InstanceAppService;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import ai.neargo.opskit.process.core.app.SlaTracker;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link ProcessClient} 的进程内实现：直接调流程应用服务（事务模板加入调用方事务 → 同进程同事务）。 */
public class EmbeddedProcessClient implements ProcessClient {

    private final InstanceAppService instances;
    private final ProcessDefinitionAppService definitions;
    private final SlaPolicyAppService slaPolicies;
    private final SlaTracker sla;
    private final TransitionLogDao logs;

    public EmbeddedProcessClient(InstanceAppService instances, ProcessDefinitionAppService definitions, SlaPolicyAppService slaPolicies, SlaTracker sla,
            TransitionLogDao logs) {
        this.instances = instances;
        this.definitions = definitions;
        this.slaPolicies = slaPolicies;
        this.sla = sla;
        this.logs = logs;
    }

    @Override
    public InstanceView start(Actor actor, StartRequest req) {
        return instances.start(actor, req);
    }

    @Override
    public InstanceView transition(Actor actor, String instanceNo, String code, TransitionRequest req) {
        return instances.transition(actor, instanceNo, code, req);
    }

    @Override
    public InstanceView get(Actor actor, String instanceNo) {
        return instances.get(actor, instanceNo);
    }

    @Override
    public List<ai.neargo.opskit.process.api.model.ActionView> actions(Actor actor, String instanceNo) {
        return instances.actions(actor, instanceNo);
    }

    @Override
    public Optional<InstanceView> snapshot(TenantContext t, String instanceNo) {
        try {
            return Optional.of(instances.get(new Actor(t, Optional.empty(), Optional.of("opskit-internal"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
                    instanceNo));
        } catch (OpsKitException e) {
            return Optional.empty();
        }
    }

    @Override
    public InstanceView updateFields(Actor actor, String instanceNo, Map<String, Object> patch) {
        return instances.updateFields(actor, instanceNo, patch);
    }

    @Override
    public void replaceScopes(Actor actor, String instanceNo, Collection<String> tags) {
        instances.replaceScopes(actor, instanceNo, tags);
    }

    @Override
    public void replaceParticipants(Actor actor, String instanceNo, String role, Collection<String> principals) {
        instances.replaceParticipants(actor, instanceNo, role, principals);
    }

    @Override
    public void fireSystem(TenantContext t, String instanceNo, String code, String by, String reason) {
        instances.fireSystem(t, instanceNo, code, by, reason);
    }

    @Override
    public Optional<ProcessDef> processDef(TenantContext t, String processCode) {
        try {
            return Optional.of(definitions.resolve(t, processCode).process().definition());
        } catch (OpsKitException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean slaPolicyExists(TenantContext t, String policyCode) {
        return slaPolicies.resolve(t, policyCode).isPresent();
    }

    @Override
    public List<SlaMetric> slaMetrics(TenantContext t, String instanceNo) {
        return sla.metrics(t, instanceNo).stream().map(m -> new SlaMetric(m.clockId(), m.policyCode(), m.metricCode(), m.name(), m.state(),
                m.startedAtMs(), m.dueAtMs(), m.pausedMs(), m.breachedAtMs(), m.stoppedAtMs())).toList();
    }

    @Override
    public List<HistoryEntry> history(TenantContext t, String instanceNo) {
        return logs.ofInstance(t.workspaceId(), instanceNo).stream().map(e -> new HistoryEntry(e.entityKind(), e.entityNo(), e.instanceNo(), e.action(),
                e.fromValue(), e.toValue(), e.seq(), e.actor(), e.onBehalfOf(), e.reason(), e.payload(), e.atMs())).toList();
    }
}
