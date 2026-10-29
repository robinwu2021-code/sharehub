package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 调库存服务的统一包装：拒绝 / 不可达 → 409 {@code error.inventory.remote}（带对方错误码与参数）。
 * 异常抛出即本地事务回滚，不会出现「本地改了、库存服务没改」的半边账。
 *
 * <p>此前这段转换在远程端口、物料、库存视图、处置出库里各写了一份（开发计划 M0 0.2 收敛）。
 * 需要逐条处理失败的批量场景（存量迁移、事件拉取、盘点状态同步）仍各自 catch {@link InventoryRemoteException}。
 */
public final class InventoryCalls {

    private static final Logger log = LoggerFactory.getLogger(InventoryCalls.class);

    private InventoryCalls() {
    }

    public static <T> T call(Supplier<T> s) {
        try {
            return s.get();
        } catch (InventoryRemoteException e) {
            log.warn("库存服务拒绝 code={} args={} status={}：本地改动已回滚，按错误码处理后重试", e.code, e.args, e.status);
            throw BizException.conflict("error.inventory.remote", e.code, String.join("；", e.args));
        }
    }

    public static void run(Runnable r) {
        call(() -> {
            r.run();
            return null;
        });
    }

    /** 库存服务未配置（地址或凭证缺）→ 409 {@code error.inventory.not_configured}。 */
    public static void requireConfigured(InventoryApi api) {
        if (!api.configured()) throw BizException.conflict("error.inventory.not_configured");
    }
}
