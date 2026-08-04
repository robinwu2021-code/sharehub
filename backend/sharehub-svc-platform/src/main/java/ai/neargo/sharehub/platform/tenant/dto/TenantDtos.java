package ai.neargo.sharehub.platform.tenant.dto;

/**
 * platform/tenant 子域出参 VO（🔒 休眠口子，仅 {@code /internal} 可见）。
 */
public final class TenantDtos {

    private TenantDtos() {
    }

    /**
     * 租户行，镜像 ops-web {@code Tenant}（该 interface 目前无页面使用，保留是为契约完整）。
     *
     * @param cabinetCount 设备数是**跨域聚合**（dev_cabinet），休眠期不为一个无 UI 的口子引入域间依赖，
     *                     恒返回 {@code null}；真开多租户时再接。
     */
    public record TenantEntry(String tenantNo, String name, String brandName, String status,
                              String plan, Integer cabinetCount, String expireAt) {
    }

    /** 租户配置项（通用键值；{@code configValue} 原样透传 JSON 文本）。 */
    public record TenantConfigEntry(String tenantNo, String category, String configKey, String configValue) {
    }
}
