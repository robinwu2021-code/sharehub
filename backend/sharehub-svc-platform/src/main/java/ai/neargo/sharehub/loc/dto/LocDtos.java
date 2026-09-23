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
     * @param lng     经度；为空的后果不在运营端，是 C 端「找附近的柜」算不出距离
     * @param lat     纬度
     */
    public record Site(String siteNo, String name, String venueNo, String venueName, String agentNo,
                      String regionId, String address, java.math.BigDecimal lng, java.math.BigDecimal lat,
                      String sceneType, int pointCount, int cabinetCount, String status) {
    }

    public record Location(String locationNo, String name, String siteNo, String siteName,
                          String spotDesc, int cabinetCount, String status) {
    }

    public record Venue(String venueNo, String name, String contact, String industry, int locationCount) {
    }

    /** 合同行，镜像前端 {@code Contract}（含附件列表，来自 {@code loc_contract_attach} 从表）。 */
    public record Contract(String contractNo, String venueName, String siteName, double shareRate,
                          double entryFee, String startAt, String endAt, String status,
                          java.util.List<ContractAttachment> attachments) {

        /** 兼容旧 8 参调用（详情/upsert 回包），附件缺省空列表。 */
        public Contract(String contractNo, String venueName, String siteName, double shareRate,
                        double entryFee, String startAt, String endAt, String status) {
            this(contractNo, venueName, siteName, shareRate, entryFee, startAt, endAt, status,
                    java.util.List.of());
        }
    }

    /** 合同附件行，镜像前端 {@code ContractAttachment}。 */
    public record ContractAttachment(String attachNo, String fileName, Long size,
                                     String uploadedBy, String uploadedAt) {
    }
}
