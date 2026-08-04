package ai.neargo.sharehub.platform.tenant.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.tenant.dto.TenantDtos.TenantConfigEntry;
import ai.neargo.sharehub.platform.tenant.dto.TenantDtos.TenantEntry;
import ai.neargo.sharehub.platform.tenant.entity.Tenant;
import ai.neargo.sharehub.platform.tenant.entity.TenantConfig;
import ai.neargo.sharehub.platform.tenant.mapper.TenantConfigMapper;
import ai.neargo.sharehub.platform.tenant.mapper.TenantMapper;
import ai.neargo.sharehub.platform.tenant.service.TenantService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户实现（🔒 休眠口子）。行为刻意保持最小：能建、能查、能配，不做任何配额校验或开通编排
 * —— 那些逻辑在真开多租户时才有确定形态，现在写等于凭空猜。
 */
@Service
public class TenantServiceImpl extends AbstractCrudService<Tenant, TenantEntry> implements TenantService {

    private final TenantConfigMapper configMapper;

    public TenantServiceImpl(TenantMapper mapper, TenantConfigMapper configMapper) {
        super(mapper);
        this.configMapper = configMapper;
    }

    @Override
    protected String keyColumn() {
        return "tenant_no";
    }

    @Override
    protected String keyOf(Tenant e) {
        return e.getTenantNo();
    }

    @Override
    protected void setKey(Tenant e, String no) {
        e.setTenantNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.TENANT; // T —— 未显式给号时取号；MAIN 是历史既有行，不受影响
    }

    @Override
    protected int keyWidth() {
        return 3;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"tenant_no", "name", "brand_name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status", "plan"};
    }

    @Override
    protected void beforeCreate(Tenant e) {
        if (e.getStatus() == null) e.setStatus("ENABLED");
    }

    @Override
    public List<TenantConfigEntry> configs(String tenantNo, String category) {
        LambdaQueryWrapper<TenantConfig> w = new LambdaQueryWrapper<TenantConfig>()
                .eq(TenantConfig::getTenantNo, tenantNo);
        if (category != null && !category.isBlank()) w.eq(TenantConfig::getCategory, category);
        w.orderByAsc(TenantConfig::getCategory).orderByAsc(TenantConfig::getConfigKey);

        return configMapper.selectList(w).stream()
                .map(TenantServiceImpl::toConfigVO)
                .toList();
    }

    @Override
    public TenantConfigEntry saveConfig(TenantConfig body) {
        if (body.getTenantNo() == null || body.getCategory() == null || body.getConfigKey() == null) {
            throw new IllegalArgumentException("tenantNo / category / configKey 均必填（三者是复合唯一键）");
        }
        TenantConfig cur = configMapper.selectOne(new LambdaQueryWrapper<TenantConfig>()
                .eq(TenantConfig::getTenantNo, body.getTenantNo())
                .eq(TenantConfig::getCategory, body.getCategory())
                .eq(TenantConfig::getConfigKey, body.getConfigKey())
                .last("limit 1"));

        if (cur == null) {
            if (body.getTenantId() == null) body.setTenantId(body.getTenantNo());
            configMapper.insert(body);
            return toConfigVO(body);
        }
        cur.setConfigValue(body.getConfigValue());
        configMapper.updateById(cur);
        return toConfigVO(cur);
    }

    private static TenantConfigEntry toConfigVO(TenantConfig e) {
        return new TenantConfigEntry(e.getTenantNo(), e.getCategory(), e.getConfigKey(), e.getConfigValue());
    }

    @Override
    protected TenantEntry toVO(Tenant e) {
        return new TenantEntry(e.getTenantNo(), e.getName(), e.getBrandName(), e.getStatus(),
                e.getPlan(), null, e.getExpireAt());
    }
}
