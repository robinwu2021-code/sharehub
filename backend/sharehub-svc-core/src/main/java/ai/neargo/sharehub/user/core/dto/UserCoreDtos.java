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

    /**
     * 开票申请**写入参**（白名单）。
     *
     * <p>此前 {@code POST /mp/user/invoices} 直接收实体 {@code UsrInvoice} —— 而这是
     * **C 端端点，构造请求的是终端用户**。service 已经把大部分字段按服务端口径覆盖掉了
     * （invoiceNo / cUserNo / title / status / appliedAt / issuedAt 都是服务端定的），
     * 但漏了一个：
     *
     * <p>{@code fileUrl} —— 出票 PDF 地址，本该开票后由服务端回填。
     * 实测 {@code setFileUrl} 在整个后端**从未被调用**，也就是说它没有任何服务端写入路径，
     * 唯一来源就是客户端请求体：用户可以在申请开票时塞一个自己的 URL 进去，
     * 之后凡是展示这张票的地方都会指向它。
     *
     * <p>改成白名单后这个字段根本进不来。{@code amount} 仍由客户端给 ——
     * 它的正确性依赖「订单已结算 + 未开过票」的校验，那条 TODO 还挂在 service 里，
     * 不在本次范围；但至少它现在是**显式声明**的入参，而不是跟着实体一起漏进来的。
     */
    public record InvoiceApplyReq(String titleNo, java.math.BigDecimal amount, String currency) {
    }

    /** 开票申请 / 发票记录。业务键前缀 {@code UINV}（与运营侧 {@code INV} 分开）。 */
    public record InvoiceItem(String invoiceNo, String titleNo, String title, BigDecimal amount,
                              String currency, String status, String fileUrl,
                              String appliedAt, String issuedAt) {
    }

    /**
     * 运营端看的 C 端开票申请行。
     *
     * <p><b>不复用 {@link InvoiceItem}</b>：那是消费者看自己的单，没有 {@code cUserNo} ——
     * 运营端拿它做列表，第一列「是谁申请的」就填不出来。
     * 同一个 DTO 服务两个受众的代价已经在订单那边付过一次（见 {@code MpTradeDtos} 类注释）。
     */
    public record CUserInvoiceRow(String invoiceNo, String cUserNo, String nickname,
                                  String titleNo, String title, BigDecimal amount, String currency,
                                  String status, String fileUrl, String rejectReason,
                                  String handledBy, String handledAt,
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
