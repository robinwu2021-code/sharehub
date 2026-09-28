package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import java.util.Map;
import org.springframework.transaction.support.TransactionOperations;

/** 运维面里需要「操作 + 审计同事务」的用例（其余直接用各自的应用服务）。 */
public class OperatorAppService {

    private final TenancyAppService tenancy;
    private final AuditTrail audit;
    private final TransactionOperations tx;

    public OperatorAppService(TenancyAppService tenancy, AuditTrail audit, TransactionOperations tx) {
        this.tenancy = tenancy;
        this.audit = audit;
        this.tx = tx;
    }

    /** @param operator 审计里的操作人，如 {@code ops:console} */
    @SystemAction("运维面：开空间")
    public WorkspaceView createWorkspace(AccountId account, CreateWorkspaceRequest req, String operator) {
        return tx.execute(s -> {
            WorkspaceView ws = tenancy.createWorkspace(account, req);
            audit.record(account, ws.workspaceId(), operator, "workspace.created", "workspace:" + ws.workspaceId(),
                    Map.of("externalRef", String.valueOf(ws.externalRef())));
            return ws;
        });
    }
}
