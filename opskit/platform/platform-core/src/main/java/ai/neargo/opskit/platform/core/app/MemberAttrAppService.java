package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.Permission;
import ai.neargo.opskit.platform.api.directory.MemberAttrDef;
import ai.neargo.opskit.platform.store.dao.MemberAttrDao;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.support.TransactionOperations;

/** 成员属性定义（21/07 O1-12）。 */
public class MemberAttrAppService {

    private final MemberAttrDao dao;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public MemberAttrAppService(MemberAttrDao dao, Clock clock, TransactionOperations tx, AuditTrail audit) {
        this.dao = dao;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
    }

    @RequiresPermission("directory:manage")
    public void upsert(Actor actor, WorkspaceId workspace, MemberAttrDef def) {
        Guards.require(actor, Permission.DIRECTORY_MANAGE);
        if (def.key() == null || !def.key().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid attribute key: " + def.key());
        }
        if (!workspace.isTemplate() && !workspace.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        tx.executeWithoutResult(s -> {
            dao.upsert(actor.tenant().accountId(), workspace, new MemberAttrDao.Row(workspace.value(), def.key(), def.name(), def.valueLabels()),
                    AuditTrail.who(actor), clock.millis());
            audit.record(actor.tenant().accountId(), workspace.value(), AuditTrail.who(actor), "member_attr.upserted", "attr:" + def.key(),
                    Map.of("values", def.valueLabels().keySet()));
        });
    }

    /**
     * 把成员的原始属性渲染成 {@code {key: {value, name?}}}（21/06 §七）。没有定义显示名的只给 value。
     *
     * @param locale 首选语种；缺该语种时退到 en，再退到任意一个
     */
    @SystemAction("列表渲染内部调用")
    public Map<String, Map<String, Object>> render(TenantContext t, Map<String, Object> attrs, String locale) {
        Map<String, MemberAttrDao.Row> defs = dao.visibleIn(t.accountId(), t.workspaceId());
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        attrs.forEach((k, v) -> {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("value", v);
            MemberAttrDao.Row def = defs.get(k);
            Map<String, String> labels = def == null || v == null ? null : def.valueLabels().get(String.valueOf(v));
            if (labels != null && !labels.isEmpty()) {
                cell.put("name", labels.getOrDefault(locale, labels.getOrDefault("en", labels.values().iterator().next())));
            }
            out.put(k, cell);
        });
        return out;
    }
}
