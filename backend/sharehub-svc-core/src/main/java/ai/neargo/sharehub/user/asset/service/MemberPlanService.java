package ai.neargo.sharehub.user.asset.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberPlanReq;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberPlanRow;

/**
 * 会员方案维护（{@code mbr_plan}）。
 *
 * <h3>此前这张表没有任何写入方</h3>
 * {@code mbr_plan} 有表、有实体、有 mapper，C 端有会员方案页读它
 * （{@code GET /mp/user/membership}），{@code MemberPlanRow} 这个出参 DTO 都声明好了
 * 并注明「运营端方案维护」—— 唯独<b>全仓没有一个端点能建或改它</b>，
 * 表里 0 行。于是 C 端会员页永远是空列表，看起来像「本平台暂无会员方案」。
 *
 * <p>启停走动作端点而不是保存字段，见 {@link MemberPlanReq}。
 */
public interface MemberPlanService {

    PageResult<MemberPlanRow> page(Integer page, Integer size, String keyword, String status);

    /** upsert：{@code planNo} 为空则新建（取号 {@code MP}），否则按业务键更新。 */
    MemberPlanRow save(MemberPlanReq req);

    /**
     * 上架 / 下架。
     *
     * @param enable true=ENABLED（C 端可见可买）；false=DISABLED
     */
    MemberPlanRow setEnabled(String planNo, boolean enable);
}
