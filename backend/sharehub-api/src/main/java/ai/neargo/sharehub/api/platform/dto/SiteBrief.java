package ai.neargo.sharehub.api.platform.dto;

/**
 * 站点摘要 —— 跨服务出参，**只含调用方真正需要的字段**。
 *
 * <p>不复用 {@code LocSite} 实体：实体带 30+ 列与租户/软删/乐观锁语义，
 * 跨服务传实体等于把 platform 的内部模型泄露给调用方，改一列就波及所有下游。
 */
public record SiteBrief(String siteNo, String name, String regionId) {
}
