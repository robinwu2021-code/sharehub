package ai.neargo.sharehub.user.ad.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdDeliveryVO;

/**
 * 投放与曝光统计（{@code GET /api/user/ad-deliveries}）—— 读 append 表 {@code ad_impression}。
 *
 * <p><b>只读</b>：曝光数据由设备投屏端上报写入，运营端不得人工增改
 * （改了就等于改广告主的结算依据）。因此本接口不提供 save/remove。
 */
public interface AdDeliveryService {

    /**
     * @param adNo   按广告活动过滤
     * @param slotNo 按广告位过滤
     * @param from   统计日下界 {@code YYYY-MM-DD}（含）；空则不限
     * @param to     统计日上界 {@code YYYY-MM-DD}（含）；空则不限
     */
    PageResult<AdDeliveryVO> page(Integer page, Integer size, String adNo, String slotNo,
                                  String from, String to);
}
