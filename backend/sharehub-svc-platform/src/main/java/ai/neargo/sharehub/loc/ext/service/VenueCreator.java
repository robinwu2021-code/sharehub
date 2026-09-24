package ai.neargo.sharehub.loc.ext.service;

/**
 * 「建场地方并返回 {@code venue_no}」的**接缝（seam）**。
 *
 * <p><b>为什么是接口而不是直接调 mapper</b>：{@code loc_venue} 的实体
 * （{@code loc.entity.LocVenue}）与其 mapper（{@code loc.mapper.LocMappers.VenueMapper}）
 * 属于 {@code loc} 主包，由**另一条工作线**维护；本 {@code loc/ext} 分片不得改动它们。
 * 进件审核通过时必须建场地方并回填 {@code venue_no}（[api/README §3.4]），于是把这一步抽成接口。
 *
 * <p><b>接缝的意义</b>：{@code loc_venue} 的 mapper 属 {@code loc} 主包，
 * 进件这个分片不得直接触碰 —— 审核流程只认这个接口，换实现一行不用改。
 *
 * <p>实现为 {@code RealVenueCreator}，调 {@code LocService.saveVenue(...)} 真落
 * {@code loc_venue} 行，业务键前缀 {@code VEN}（{@link ai.neargo.sharehub.common.BizKey#VENUE}）。
 * （此前是只取号不落行的占位实现，审核通过后场地方列表查无此人。）
 */
public interface VenueCreator {

    /**
     * 建场地方，返回其业务键 {@code venue_no}。
     *
     * @param venueName 场地方名称（来自进件）
     * @param contact   联系方式（掩码；明文落 pb_pii）
     * @param industry  行业
     * @return 新建场地方的 {@code venue_no}，非空
     */
    String create(String venueName, String contact, String industry);
}
