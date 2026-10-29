package ai.neargo.sharehub.inv.remote;

import java.util.List;

/** 库存服务拒绝或不可达。{@code code} 是库存服务的错误键（inventory.xxx），不可达时为 inventory.unreachable。 */
public class InventoryRemoteException extends RuntimeException {

    public final int status;
    public final String code;
    public final List<String> args;

    public InventoryRemoteException(int status, String code, List<String> args, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.args = args == null ? List.of() : List.copyOf(args);
    }
}
