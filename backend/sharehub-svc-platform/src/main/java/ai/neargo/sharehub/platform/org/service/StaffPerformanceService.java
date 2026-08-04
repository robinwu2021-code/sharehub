package ai.neargo.sharehub.platform.org.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.StaffPerformance;

/** 员工绩效报表。**只读**：数据由结算/工单侧按账期写入，运营端不手改。 */
public interface StaffPerformanceService {

    /**
     * 绩效分页。
     *
     * @param period 账期 {@code YYYY-MM}；空则不限
     * @param role   统计口径角色码 OPS/CS/FINANCE/BD；空则不限
     */
    PageResult<StaffPerformance> page(Integer page, Integer size, String keyword, String period, String role);
}
