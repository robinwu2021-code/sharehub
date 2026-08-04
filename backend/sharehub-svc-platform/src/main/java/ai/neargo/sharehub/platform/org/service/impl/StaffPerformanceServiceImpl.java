package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.StaffPerformance;
import ai.neargo.sharehub.platform.org.entity.IamStaffPerf;
import ai.neargo.sharehub.platform.org.mapper.IamStaffPerfMapper;
import ai.neargo.sharehub.platform.org.service.StaffPerformanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工绩效实现 —— 纯读报表，故手写而非继承 CRUD 基类：
 * 它的业务键是复合 UK(employee_no, period)，套 {@code keyColumn()} 单列语义会说谎。
 *
 * <p>默认排序 {@code period DESC, score DESC}：报表页第一眼要看的是「最近账期里谁最高」。
 */
@Service
public class StaffPerformanceServiceImpl implements StaffPerformanceService {

    private final IamStaffPerfMapper mapper;

    public StaffPerformanceServiceImpl(IamStaffPerfMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<StaffPerformance> page(Integer page, Integer size, String keyword, String period, String role) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<IamStaffPerf> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(IamStaffPerf::getEmployeeNo, keyword).or().like(IamStaffPerf::getEmployeeName, keyword));
        }
        if (period != null && !period.isBlank()) w.eq(IamStaffPerf::getPeriod, period);
        if (role != null && !role.isBlank()) w.eq(IamStaffPerf::getRole, role);
        w.orderByDesc(IamStaffPerf::getPeriod).orderByDesc(IamStaffPerf::getScore);

        Page<IamStaffPerf> r = mapper.selectPage(new Page<>(p, s), w);
        List<StaffPerformance> rows = r.getRecords().stream().map(StaffPerformanceServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static StaffPerformance toVO(IamStaffPerf e) {
        return new StaffPerformance(e.getEmployeeNo(), e.getEmployeeName(), e.getRole(), e.getPeriod(),
                e.getHandled(), e.getAvgResolveMins(), e.getScore());
    }
}
