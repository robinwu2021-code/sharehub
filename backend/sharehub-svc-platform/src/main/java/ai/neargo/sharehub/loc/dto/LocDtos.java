package ai.neargo.sharehub.loc.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * loc 域的出入参 DTO。
 *
 * <p>原先住在顶层 {@code dto.Dto} 那个骨架期共享集合里 —— 那让 loc 域**必须依赖 app**，
 * 是拆 Maven 模块的硬阻塞。DTO 属于它描述的那个域，搬回来是归位不是重构。
 */
public final class LocDtos {

    private LocDtos() {
    }

    /**
     * @param venueNo 归属场地方编号。**分成按它走**，不按 venueName ——
     *                种子里就有同名场地方，按名字连必然连错
     * @param regionName 区域展示名（冗余自 `md_region.name`）。**存 ID 是因为区域名会变、ID 不会**，
     *                   但列表与详情都要展示，所以出参带一份 —— 不带的话前端只能显示
     *                   `undefined`（2026-09-23 站点详情抽屉上就是这样）
     * @param lng     经度；为空的后果不在运营端，是 C 端「找附近的柜」算不出距离
     * @param lat     纬度
     * @param pointCount    点位数。**只出不进**：它是按关系聚合出来的数，保存时不回写
     *                      （装箱类型而非 int —— 请求里没带这个字段时 int 会让 Jackson 抛
     *                      「Cannot map null into type int」，而调用方看到的是 500 服务器错误，
     *                      完全指不到「少传了一个本就不该传的字段」）
     * @param cabinetCount  机柜数，同上
     */
    public record Site(String siteNo, String name, String venueNo, String venueName, String agentNo,
                      String brandNo, String regionId, String regionName,
                      String address, java.math.BigDecimal lng, java.math.BigDecimal lat,
                      String sceneType, Integer pointCount, Integer cabinetCount, String status,
                      String archivedAt) {
    }

    /**
     * @param cabinetCount 机柜数。**可能为 null** —— platform 算不出（`dev_cabinet` 属于 core），
     *                     null 表示「这里答不了」，与 0（「确实一台都没有」）是两回事。
     *                     装箱类型还有第二个作用：请求里不带这个字段时，int 会让 Jackson 抛
     *                     「Cannot map null into type int」，调用方看到的是 500。
     */
    public record Location(String locationNo, String name, String siteNo, String siteName,
                          String spotDesc, Integer cabinetCount, String status) {
    }

    /**
     * @param archivedAt 归档时间；`null` = 在用。
     *
     * <p><b>必须带出来</b>：运营端靠它把归档行置灰、并在「归档时间」列显示时间。
     * 不回这个字段，勾上「显示已归档」之后归档行与在用行**长得完全一样** ——
     * 而列表默认已按它过滤，所以平时看不出少了什么。
     */
    public record Venue(String venueNo, String name, String contact, String industry,
                        int locationCount, String archivedAt) {
    }

    /**
     * 合同行，镜像前端 {@code Contract}（含附件列表，来自 {@code loc_contract_attach} 从表）。
     *
     * <p><b>必须带 venueNo / siteNo</b>：合同是场地方分成的唯一依据，按**编号**连；
     * 名字只是展示冗余（同一商场不同楼层会有同名站点，按名字连必然连错）。
     * 列与实体一直都有，只是这个读 DTO 没带出来 —— 于是运营端打开「编辑合同」时
     * 场地方/站点两个必填下拉是空的，表单根本提交不了。
     */
    public record Contract(String contractNo, String venueNo, String siteNo,
                          String venueName, String siteName, double shareRate,
                          double entryFee, String startAt, String endAt, String status,
                          java.util.List<ContractAttachment> attachments) {

        /** 兼容旧调用（详情/upsert 回包），附件缺省空列表。 */
        public Contract(String contractNo, String venueNo, String siteNo, String venueName,
                        String siteName, double shareRate,
                        double entryFee, String startAt, String endAt, String status) {
            this(contractNo, venueNo, siteNo, venueName, siteName, shareRate, entryFee,
                    startAt, endAt, status, java.util.List.of());
        }
    }

    /** 合同附件行，镜像前端 {@code ContractAttachment}。 */
    public record ContractAttachment(String attachNo, String fileName, Long size,
                                     String uploadedBy, String uploadedAt) {
    }
}
