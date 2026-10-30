package ai.neargo.sharehub.inv.port;

import ai.neargo.sharehub.api.platform.port.SysParamWriteGuard;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.InventoryOperationJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 切换库存引擎（{@code inv.engine}）是受控操作（对接明细 F09、§9.3 第 6 条）：
 *
 * <ul>
 *   <li>有未完成的库存操作时不许切 —— 在途操作按创建时固定的引擎恢复，切走就恢复不了；</li>
 *   <li>切到 SERVICE 前库存服务须已配置且可达 —— 否则切过去所有库存写操作立刻失败。</li>
 * </ul>
 *
 * <p>没有反向的「自动回落 LOCAL」：库存服务故障时写操作直接失败，不改本地账。
 */
@Component
public class InventoryEngineGuard implements SysParamWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(InventoryEngineGuard.class);

    private final RemoteInventoryPort remote;
    private final InventoryOperationJournal journal;

    public InventoryEngineGuard(RemoteInventoryPort remote, InventoryOperationJournal journal) {
        this.remote = remote;
        this.journal = journal;
    }

    @Override
    public boolean handles(String paramKey) {
        return InventoryEngineRouter.ENGINE_KEY.equals(paramKey);
    }

    @Override
    public void check(String paramKey, String oldValue, String newValue) {
        long pending = journal.unfinished();
        if (pending > 0) throw BizException.conflict("error.inventory.engine_switch_pending", pending);
        if (!"SERVICE".equals(newValue)) return;
        if (!remote.api().configured()) throw BizException.conflict("error.inventory.not_configured");
        try {
            remote.api().warehouses();
        } catch (InventoryRemoteException e) {
            log.warn("切换库存引擎到 SERVICE 被拒：库存服务不可达 code={}，先恢复库存服务再切", e.code);
            throw BizException.conflict("error.inventory.engine_switch_unreachable", e.code);
        }
        log.info("库存引擎切换 {} → {}", oldValue, newValue);
    }
}
