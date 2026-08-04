package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeOrderRow;
import ai.neargo.sharehub.user.asset.entity.UsrRechargeOrder;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargeOrderMapper;
import ai.neargo.sharehub.user.asset.service.RechargeOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/** 充值订单实现（运营端只读列表）。 */
@Service
public class RechargeOrderServiceImpl implements RechargeOrderService {

    private final UsrRechargeOrderMapper mapper;

    public RechargeOrderServiceImpl(UsrRechargeOrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<RechargeOrderRow> page(Integer page, Integer size, String keyword,
                                             String status, String from, String to) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrRechargeOrder> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrRechargeOrder::getRechargeNo, keyword)
                    .or().like(UsrRechargeOrder::getCUserNo, keyword)
                    .or().like(UsrRechargeOrder::getNickname, keyword)
                    .or().like(UsrRechargeOrder::getPspTxnNo, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(UsrRechargeOrder::getStatus, status);
        // 时间范围比对 created_at 而不是 paid_at，见接口注释
        if (from != null && !from.isBlank()) w.ge(UsrRechargeOrder::getCreatedAt, from);
        if (to != null && !to.isBlank()) w.le(UsrRechargeOrder::getCreatedAt, to);
        w.orderByDesc(UsrRechargeOrder::getId);

        Page<UsrRechargeOrder> r = mapper.selectPage(new Page<>(p, s), w);
        List<RechargeOrderRow> rows = r.getRecords().stream()
                .map(e -> new RechargeOrderRow(e.getRechargeNo(), e.getCUserNo(), e.getNickname(),
                        e.getPackageNo(), e.getPayAmount(), e.getGiftAmount(), e.getCreditAmount(),
                        e.getCurrency(), e.getChannelCode(), e.getStatus(),
                        e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                        e.getPaidAt(), e.getPspTxnNo()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }
}
