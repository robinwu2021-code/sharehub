package ai.neargo.sharehub.gw.service.impl;

import ai.neargo.sharehub.gw.VendorStatus;

import ai.neargo.sharehub.gw.driver.DriverRegistry;
import ai.neargo.sharehub.gw.dto.GwDtos.VendorProbeResult;
import ai.neargo.sharehub.gw.dto.GwDtos.VendorVO;
import ai.neargo.sharehub.gw.entity.GwVendor;
import ai.neargo.sharehub.gw.entity.GwVendorConfig;
import ai.neargo.sharehub.gw.mapper.GwVendorConfigMapper;
import ai.neargo.sharehub.gw.mapper.GwVendorMapper;
import ai.neargo.sharehub.gw.service.VendorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** 供应商目录/配置实现。密钥出参不经此类（列表 VO 无密钥字段），配置读回走独立端点时再掩码。 */
@Service
public class VendorServiceImpl implements VendorService {

    private final GwVendorMapper vendors;
    private final GwVendorConfigMapper configs;
    private final DriverRegistry drivers;

    public VendorServiceImpl(GwVendorMapper vendors, GwVendorConfigMapper configs, DriverRegistry drivers) {
        this.vendors = vendors;
        this.configs = configs;
        this.drivers = drivers;
    }

    @Override
    public List<VendorVO> list() {
        return vendors.selectList(new LambdaQueryWrapper<GwVendor>().orderByAsc(GwVendor::getVendorCode))
                .stream().map(VendorServiceImpl::toVO).toList();
    }

    @Override
    public VendorVO saveConfig(String vendorCode, Map<String, Object> body) {
        GwVendor v = byCode(vendorCode);
        if (v == null) {
            v = new GwVendor();
            v.setVendorCode(vendorCode);
            v.setDeviceCount(0);
        }
        if (body.get("name") != null) v.setName(String.valueOf(body.get("name")));
        if (body.get("accessMode") != null) v.setAccessMode(String.valueOf(body.get("accessMode")));
        v.setStatus(body.get("status") == null ? (v.getStatus() == null ? VendorStatus.ENABLED.name() : v.getStatus())
                : String.valueOf(body.get("status")));
        if (body.containsKey("apiBase")) {
            v.setApiBase(body.get("apiBase") == null ? null : String.valueOf(body.get("apiBase")));
        }
        if (v.getId() == null) vendors.insert(v); else vendors.updateById(v);

        GwVendorConfig c = configs.selectOne(new LambdaQueryWrapper<GwVendorConfig>()
                .eq(GwVendorConfig::getVendorCode, vendorCode).last("limit 1"));
        if (c == null) {
            c = new GwVendorConfig();
            c.setVendorCode(vendorCode);
            c.setTenantId(ai.neargo.sharehub.auth.SecurityUtils.tenantId());
        }
        if (body.containsKey("apiBase")) c.setApiBase(v.getApiBase());
        // 掩码占位（全 * 或含 ****）视为「未修改」——防止读回再存把真密钥抹掉
        putSecret(body, "appKey", c::setAppKey);
        putSecret(body, "appSecret", c::setAppSecret);
        putSecret(body, "verifyKey", c::setVerifyKey);
        if (body.get("ipWhitelist") != null) c.setIpWhitelist(String.valueOf(body.get("ipWhitelist")));
        if (c.getId() == null) configs.insert(c); else configs.updateById(c);
        return toVO(v);
    }

    @Override
    public VendorProbeResult probe(String vendorCode) {
        GwVendor v = byCode(vendorCode);
        if (v == null) throw new IllegalArgumentException("供应商不存在: " + vendorCode);
        String endpoint = v.getApiBase() == null || v.getApiBase().isBlank()
                ? "gateway://" + vendorCode : v.getApiBase();
        boolean hasDriver = drivers.manifests().stream()
                .anyMatch(m -> m.vendorCode().equalsIgnoreCase(vendorCode));
        boolean enabled = VendorStatus.ENABLED.name().equals(v.getStatus());
        boolean ok = hasDriver && enabled;
        String message = !hasDriver ? "未注册接入驱动，无法探测"
                : !enabled ? "供应商已停用，跳过探测"
                : "驱动就绪（占位探测，真实链路待 access-gateway 裂解）";
        return new VendorProbeResult(vendorCode, ok, endpoint, ok ? 5L : 0L,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), message,
                "driver=" + (hasDriver ? "registered" : "absent") + ", status=" + v.getStatus());
    }

    private GwVendor byCode(String vendorCode) {
        return vendors.selectOne(new LambdaQueryWrapper<GwVendor>()
                .eq(GwVendor::getVendorCode, vendorCode).last("limit 1"));
    }

    private static void putSecret(Map<String, Object> body, String key, java.util.function.Consumer<String> setter) {
        Object val = body.get(key);
        if (val == null) return;
        String s = String.valueOf(val);
        if (s.isBlank() || s.contains("****") || s.chars().allMatch(ch -> ch == '*')) return;
        setter.accept(s);
    }

    private static VendorVO toVO(GwVendor v) {
        return new VendorVO(v.getVendorCode(), v.getName(), v.getAccessMode(),
                v.getStatus(), v.getApiBase(), v.getDeviceCount() == null ? 0 : v.getDeviceCount());
    }
}
