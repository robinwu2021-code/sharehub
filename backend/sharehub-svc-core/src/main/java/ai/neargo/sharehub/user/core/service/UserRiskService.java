package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserRisk;

/**
 * 风控用户（usr_credit）。有业务口径（风险等级由信用分/行为派生）→ 手写，不走通用 CRUD。
 * 本表在 DDL 里无 {@code version}/{@code deleted}，实体也不继承 {@code BaseEntity}，
 * 因此结构上就无法接 {@code AbstractCrudService}。
 */
public interface UserRiskService {

    /**
     * 风控用户分页。
     *
     * @param keyword   匹配 {@code risk_no}/{@code c_user_no}
     * @param riskLevel HIGH/MEDIUM/LOW；空则不过滤
     */
    PageResult<UserRisk> page(Integer page, Integer size, String keyword, String riskLevel);
}
