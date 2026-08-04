package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadMapper;
import ai.neargo.sharehub.loc.ext.service.LeadService;
import org.springframework.stereotype.Service;

import java.util.Set;

/** BD 商机实现：全部行为来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class LeadServiceImpl extends AbstractCrudService<LocLead, Lead> implements LeadService {

    /** [db-design §3.4] 的 stage 取值域；越界值直接拒，避免脏枚举污染看板分组。 */
    private static final Set<String> STAGES =
            Set.of("NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST");

    public LeadServiceImpl(LocLeadMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "lead_no";
    }

    @Override
    protected String keyOf(LocLead e) {
        return e.getLeadNo();
    }

    @Override
    protected void setKey(LocLead e, String no) {
        e.setLeadNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.LEAD;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"lead_no", "venue_name", "owner"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"stage", "owner", "regionId"};
    }

    @Override
    protected String orderColumn() {
        return "updated_at";
    }

    @Override
    protected void beforeCreate(LocLead e) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage("NEW");
        if (e.getExpectSites() == null) e.setExpectSites(0);
        checkStage(e.getStage());
    }

    @Override
    protected void beforeUpdate(LocLead e, LocLead current) {
        if (e.getStage() == null || e.getStage().isBlank()) e.setStage(current.getStage());
        checkStage(e.getStage());
    }

    private static void checkStage(String stage) {
        if (!STAGES.contains(stage)) {
            throw new IllegalArgumentException("商机阶段非法: " + stage);
        }
    }

    @Override
    protected Lead toVO(LocLead e) {
        return new Lead(e.getLeadNo(), e.getVenueName(), e.getContact(), e.getStage(),
                e.getOwner(), e.getExpectSites(), e.getNextFollowAt(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }
}
