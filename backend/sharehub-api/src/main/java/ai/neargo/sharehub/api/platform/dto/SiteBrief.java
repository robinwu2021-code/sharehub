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
 *
 * <p>2026-09-25 追加（追加在末尾，现有构造点逐一补参）：
 * {@code status}（借出校验：只有 ACTIVE 可借）· {@code openHours}（告警营业时间计时）·
 * {@code operateAgentNo}（生效中的 OPERATE 伙伴）/ {@code opsEmployeeNo}（平台员工责任人）—— 自动派单依据 ·
 * {@code agentNo}（数据范围锚点）· {@code surveyPassed}（最近一次现场勘测是否通过，null = 从未勘测；首台上线门禁用）。
 */
public record SiteBrief(String siteNo, String name, String regionId,
                        String venueNo, String sceneType, String brandNo,
                        String status, String openHours, String operateAgentNo, String opsEmployeeNo, String agentNo,
                        Boolean surveyPassed,
                        // 批次 G1：首台设备上线日（低效判定只看上线满 N 个月的站点）
                        java.time.LocalDate firstLiveOn) {

    /** 兼容构造点（无首次上线日）。 */
    public SiteBrief(String siteNo, String name, String regionId, String venueNo, String sceneType, String brandNo,
                     String status, String openHours, String operateAgentNo, String opsEmployeeNo, String agentNo,
                     Boolean surveyPassed) {
        this(siteNo, name, regionId, venueNo, sceneType, brandNo, status, openHours, operateAgentNo, opsEmployeeNo, agentNo,
                surveyPassed, null);
    }

    /** 兼容构造点（无勘测信息）。 */
    public SiteBrief(String siteNo, String name, String regionId, String venueNo, String sceneType, String brandNo,
                     String status, String openHours, String operateAgentNo, String opsEmployeeNo, String agentNo) {
        this(siteNo, name, regionId, venueNo, sceneType, brandNo, status, openHours, operateAgentNo, opsEmployeeNo, agentNo, null, null);
    }

    /** 兼容旧构造点。 */
    public SiteBrief(String siteNo, String name, String regionId, String venueNo, String sceneType, String brandNo) {
        this(siteNo, name, regionId, venueNo, sceneType, brandNo, null, null, null, null, null, null, null);
    }

    /** 营业中 —— 唯一可借的站点状态（停借保还：其余状态只拦借、不拦还）。 */
    public boolean rentable() {
        return "ACTIVE".equals(status);
    }
}
