package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.SysParamEntry;
import ai.neargo.sharehub.platform.sys.entity.SysParam;
import ai.neargo.sharehub.platform.sys.mapper.SysParamMapper;
import ai.neargo.sharehub.platform.sys.service.SysParamService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 系统参数实现。
 *
 * <p><b>覆盖 {@code selectByKey} 是必须的</b>：基类默认只按业务键单列查，
 * 而本表 UK 是 {@code (tenant_id, param_key)} —— 不带 tenantId 会查到别家的参数。
 */
@Service
public class SysParamServiceImpl extends AbstractCrudService<SysParam, SysParamEntry> implements SysParamService {

    public SysParamServiceImpl(SysParamMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "param_key";
    }

    @Override
    protected String keyOf(SysParam e) {
        return e.getParamKey();
    }

    @Override
    protected void setKey(SysParam e, String no) {
        e.setParamKey(no);
    }

    // keyPrefix() 不覆盖 → 自然键（如 order.max_duration_min），新建必须显式给 paramKey

    /** 复合 UK {@code (tenant_id, param_key)}：按键查询必须带隔离键。 */
    @Override
    protected SysParam selectByKey(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<SysParam>()
                .eq("param_key", no)
                .eq("tenant_id", SysCtx.tenantId())
                .last("limit 1"));
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"param_key", "label", "value"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"groupName"};
    }

    @Override
    protected String orderColumn() {
        return "param_key";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected SysParamEntry toVO(SysParam e) {
        return new SysParamEntry(e.getParamKey(), e.getLabel(), e.getValue(),
                e.getGroupName(), SysCtx.fmt(e.getUpdatedAt()));
    }
}
