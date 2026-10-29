package ai.neargo.sharehub.inv.remote;

/** 固定地址寻址（默认）：{@code sharehub.inventory.base-url}。 */
public class StaticInventoryEndpoint implements InventoryEndpoint {

    private final String baseUrl;

    public StaticInventoryEndpoint(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    @Override
    public String mode() {
        return "STATIC";
    }

    @Override
    public boolean configured() {
        return !baseUrl.isBlank();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }
}
