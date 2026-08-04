package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店自助进件（loc_venue_onboarding，[db-design §3.4]）。
 *
 * <p>审核链路是有业务规则的：{@code PENDING → APPROVED/REJECTED} 单向不可回退，
 * 且**通过时必须先建 {@code loc_venue} 再回填 {@link #venueNo}**（[api/README §3.4]）。
 * 因此本表不走通用 CRUD，由
 * {@code ai.neargo.sharehub.loc.ext.service.VenueOnboardingService} 手写。
 *
 * <p>业务键前缀 {@code OB}。{@code reviewAt} 可空，语义为「尚未审核」（[db-design §1.5]）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loc_venue_onboarding")
public class LocVenueOnboarding extends BaseEntity {

    private String onboardingNo;

    private String regionId;

    private String venueName;

    /** 联系方式（掩码；明文落 pb_pii）。 */
    private String contact;

    private String industry;

    /** 营业执照等附件的 JSON 原文。 */
    private String attach;

    private String requestedAt;

    /** PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 审核时刻；空 = 尚未审核。 */
    private String reviewAt;

    /** 审核人 employee_no。 */
    private String reviewBy;

    private String reviewNote;

    /** 通过后回填的 {@code loc_venue.venue_no}；驳回或待审为空。 */
    private String venueNo;
}
