package ai.neargo.opskit.commons.jdbc;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;

/**
 * 条件更新的结果判定：0 行 = 别人先改了（409），1 行 = 成功，多于 1 行 = 条件写错了（定位不到唯一一行）。
 * 所有迁移、领取、决定、确认回复都走这里，保证「并发只一人成功」的表现一致。
 */
public final class ConditionalUpdate {

    private ConditionalUpdate() {
    }

    public static void requireExactlyOne(int affectedRows, String what) {
        if (affectedRows == 1) {
            return;
        }
        if (affectedRows == 0) {
            throw new OpsKitException(CommonErrors.STATE_CHANGED, what + " was changed concurrently");
        }
        throw new IllegalStateException("conditional update hit " + affectedRows + " rows, expected 1: " + what);
    }
}
