package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.SiteBrief;

import java.util.List;

/**
 * 站点的系统读 + 系统动作（设备上线驱动站点营业、告警判定遍历营业站点）。<b>豁免数据范围</b>。
 *
 * <p>与 {@link SiteQueryPort} 分开的理由同 core 的 CabinetQueryPort / DeviceOwnershipPort：
 * 那个接口保留数据范围，本接口必须豁免 —— 混在一起迟早有人照着隔壁的写法把范围关错。
 */
public interface SiteStatePort {

    /** 状态在给定集合内的站点（分页遍历：afterId 之后取 limit 条，按 id 升序）。 */
    List<SiteBrief> listByStatus(List<String> statuses, long afterId, int limit);

    /** 站点主键 id（配合 listByStatus 翻页）。 */
    long idOf(String siteNo);
}
