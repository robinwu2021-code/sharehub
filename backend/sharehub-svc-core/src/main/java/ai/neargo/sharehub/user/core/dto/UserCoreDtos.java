package ai.neargo.sharehub.user.core.dto;

import java.math.BigDecimal;

/**
 * user/core 子域出参 VO（风控 + C 端专属）。
 *
 * <p>字段镜像 ops-web {@code lib/types/user.ts}（{@code UserRisk}/{@code UserBlacklist}/
 * {@code FreeUserWhitelist}）与 c-app 的消息/发票形状。域内 dto 文件，不往顶层
 * {@code dto/Dto.java} 追加（那个文件已冻结）。
 */
public final class UserCoreDtos {

    private UserCoreDtos() {
    }

    // ——————————————————————— 风控（运营端）———————————————————————

    /** 风控用户行，镜像前端 {@code UserRisk}。 */
    public record UserRisk(String riskNo, String userNo, String nickname, String phone,
                           Integer creditScore, String riskLevel, String reason, String flaggedAt) {
    }

    /**
     * 黑名单行，镜像前端 {@code UserBlacklist}。
     * {@code releasedAt} 为空表示尚未解除；解除后记录仍在（{@code status=RELEASED}），列表可回溯。
     */
    public record UserBlacklist(String blacklistNo, String userNo, String nickname, String phone,
                                String reason, String blacklistedAt, String blacklistedBy,
                                String releasedAt, String releasedBy, String status) {
    }

    /**
     * 免费白名单行，镜像前端 {@code FreeUserWhitelist}（多带 {@code whitelistNo}：
     * 前端按 {@code userNo} 寻址，服务端仍需业务键定位记录）。
     */
    public record FreeUserWhitelist(String whitelistNo, String userNo, String nickname, String phone,
                                    String reason, String quotaType, BigDecimal quotaValue,
                                    BigDecimal usedValue, String currency,
                                    String validFrom, String validTo, String grantedBy, String status) {
    }

    // ——————————————————————— C 端专属 ———————————————————————

    /** 站内消息。实体列是 {@code is_read}（MySQL 保留字规避），VO 里回到业务语义 {@code read}。 */
    public record MessageItem(String messageNo, String type, String title, String body,
                              boolean read, String readAt, String createdAt) {
    }

    /** 收藏门店。{@code siteName} 由 service 关联 {@code loc_site} 补齐，未命中为 null。 */
    public record FavoriteItem(String siteNo, String siteName, String createdAt) {
    }

    /** 发票抬头。 */
    public record InvoiceTitleItem(String titleNo, String type, String title, String vatTrn,
                                   boolean isDefault) {
    }

    /** 开票申请 / 发票记录。业务键前缀 {@code UINV}（与运营侧 {@code INV} 分开）。 */
    public record InvoiceItem(String invoiceNo, String titleNo, String title, BigDecimal amount,
                              String currency, String status, String fileUrl,
                              String appliedAt, String issuedAt) {
    }

    /** 注销申请（PDPL 冷静期）。{@code coolingUntil} 之前用户可撤销。 */
    public record LogoffItem(String cUserNo, String requestedAt, String coolingUntil,
                             String status, String purgedAt) {
    }

    /**
     * 用户档案行，镜像前端 {@code CUser}。{@code phone} 已脱敏；
     * {@code orders} 由 portal 门面回填（跨域计数，见 {@code UserQueryService} 注释）。
     */
    public record CUserRow(String cUserNo, String nickname, String avatar, String phone,
                           Integer creditScore, Boolean blacklisted, Long orders, String registeredAt) {
    }
}
