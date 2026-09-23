package ai.neargo.sharehub.api.platform.dto;

/**
 * 站点摘要 —— 跨服务出参，**只含调用方真正需要的字段**。
 *
 * <p>不复用 {@code LocSite} 实体：实体带 30+ 列与租户/软删/乐观锁语义，
 * 跨服务传实体等于把 platform 的内部模型泄露给调用方，改一列就波及所有下游。
 *
 * <p>2026-09-23 加 {@code venueNo} / {@code sceneType}（ADR-028 取价的 VENUE 与 SCENE 两层）。
 * 加字段而不是新开一个 {@code SitePricingBrief}：同一个站点两份摘要，
 * 早晚会出现「这边有那边没有」的不一致，而这两个字段本来就是站点的基本属性。
 *
 * @param venueNo   归属场地方 —— VENUE 层取价用（机场统一价这类，约束来自进场合同）
 * @param sceneType 场景（商场 / 机场 / 医院…）—— SCENE 层取价用
 * @param brandNo   以哪个品牌运营 —— ADR-028 的品牌过滤条件（B1 落地后才有值）
 */
public record SiteBrief(String siteNo, String name, String regionId,
                        String venueNo, String sceneType, String brandNo) {
}
