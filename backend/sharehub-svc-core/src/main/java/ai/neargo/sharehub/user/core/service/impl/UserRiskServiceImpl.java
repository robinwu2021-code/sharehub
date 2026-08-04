package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserRisk;
import ai.neargo.sharehub.user.core.entity.UsrCredit;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrCreditMapper;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import ai.neargo.sharehub.user.core.service.UserRiskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 风控用户实现。昵称按批回填（{@link NicknameLookup}），phone 属 PII 不随列表出。 */
@Service
public class UserRiskServiceImpl implements UserRiskService {

    private final UsrCreditMapper mapper;
    private final NicknameLookup nicknames;

    public UserRiskServiceImpl(UsrCreditMapper mapper, NicknameLookup nicknames) {
        this.mapper = mapper;
        this.nicknames = nicknames;
    }

    @Override
    public PageResult<UserRisk> page(Integer page, Integer size, String keyword, String riskLevel) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrCredit> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrCredit::getRiskNo, keyword).or().like(UsrCredit::getCUserNo, keyword));
        }
        if (riskLevel != null && !riskLevel.isBlank()) w.eq(UsrCredit::getRiskLevel, riskLevel);
        w.orderByDesc(UsrCredit::getId);

        Page<UsrCredit> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, String> nick = nicknames.byUserNos(r.getRecords().stream().map(UsrCredit::getCUserNo).toList());

        List<UserRisk> rows = r.getRecords().stream()
                .map(e -> new UserRisk(e.getRiskNo(), e.getCUserNo(), nick.get(e.getCUserNo()), null,
                        e.getScore(), e.getRiskLevel(), e.getReason(), e.getFlaggedAt()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }
}
