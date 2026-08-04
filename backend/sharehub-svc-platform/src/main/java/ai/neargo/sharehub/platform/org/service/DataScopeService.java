package ai.neargo.sharehub.platform.org.service;

import ai.neargo.sharehub.platform.org.dto.OrgDtos.DataScopeEntry;

/**
 * 数据权限（iam_data_scope）保存 —— 补 [api/README §7.1] 的 **G7 缺口**：
 * 前端「数据权限抽屉」此前 {@code onSave} 只 invalidate 查询、不调 API，配置保存即丢。
 *
 * <p>UK(subject_type, subject_no) → 每个主体至多一行，保存是 **upsert**。
 * 角色级与员工级并存时取并集，判定在 {@code auth} 域（本服务只负责落库）。
 */
public interface DataScopeService {

    /** 读；不存在返回 {@code null}。 */
    DataScopeEntry get(String subjectType, String subjectNo);

    /**
     * upsert 数据范围。
     *
     * @param subjectType ROLE / EMPLOYEE（其它值拒绝）
     * @param scopeType   ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF
     * @param scopeRefs   范围明细；{@code ALL}/{@code SELF} 语义上不需要，落库前会被清空
     */
    DataScopeEntry save(String subjectType, String subjectNo, String scopeType, String scopeRefs);
}
