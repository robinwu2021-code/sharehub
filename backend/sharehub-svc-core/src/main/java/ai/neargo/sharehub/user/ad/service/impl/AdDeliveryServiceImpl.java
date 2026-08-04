package ai.neargo.sharehub.user.ad.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdDeliveryVO;
import ai.neargo.sharehub.user.ad.entity.AdImpression;
import ai.neargo.sharehub.user.ad.mapper.AdImpressionMapper;
import ai.neargo.sharehub.user.ad.service.AdDeliveryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/** 曝光统计实现（只读，append 表）。 */
@Service
public class AdDeliveryServiceImpl implements AdDeliveryService {

    private final AdImpressionMapper mapper;

    public AdDeliveryServiceImpl(AdImpressionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<AdDeliveryVO> page(Integer page, Integer size, String adNo, String slotNo,
                                         String from, String to) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<AdImpression> w = new LambdaQueryWrapper<AdImpression>()
                .eq(adNo != null && !adNo.isBlank(), AdImpression::getAdNo, adNo)
                .eq(slotNo != null && !slotNo.isBlank(), AdImpression::getSlotNo, slotNo)
                .ge(from != null && !from.isBlank(), AdImpression::getStatDate, from)
                .le(to != null && !to.isBlank(), AdImpression::getStatDate, to)
                .orderByDesc(AdImpression::getStatDate)
                .orderByDesc(AdImpression::getId);

        Page<AdImpression> r = mapper.selectPage(new Page<>(p, s), w);
        List<AdDeliveryVO> rows = r.getRecords().stream().map(AdDeliveryServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static AdDeliveryVO toVO(AdImpression e) {
        return new AdDeliveryVO(e.getDeliveryNo(), e.getAdNo(), e.getSlotNo(), e.getCabinetNo(),
                e.getImpressions(), e.getPlays(), e.getStatDate());
    }
}
