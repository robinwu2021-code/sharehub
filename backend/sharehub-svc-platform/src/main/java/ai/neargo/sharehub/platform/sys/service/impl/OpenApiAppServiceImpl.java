package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.OpenApiApp;
import java.util.UUID;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ai.neargo.sharehub.platform.sys.entity.OpenapiApp;
import ai.neargo.sharehub.platform.sys.mapper.OpenapiAppMapper;
import ai.neargo.sharehub.platform.sys.service.OpenApiAppService;
import org.springframework.stereotype.Service;

/**
 * 开放平台应用实现。
 *
 * <p><b>密钥不出参</b>：{@link #toVO} 刻意不带 {@code appSecretHash}——
 * 哈希虽不可逆，但泄漏后可离线撞库，且前端从无用途。
 * 更新时若 body 未带哈希，{@link #beforeUpdate} 保留库里原值，避免「改个限流把密钥清空」。
 */
@Service
public class OpenApiAppServiceImpl extends AbstractCrudService<OpenapiApp, OpenApiApp> implements OpenApiAppService {

    public OpenApiAppServiceImpl(OpenapiAppMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "app_no";
    }

    @Override
    protected String keyOf(OpenapiApp e) {
        return e.getAppNo();
    }

    @Override
    protected void setKey(OpenapiApp e, String no) {
        e.setAppNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.OPENAPI_APP; // APP
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"app_no", "name", "app_key"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status"};
    }

    @Override
    protected void beforeCreate(OpenapiApp e) {
        if (e.getStatus() == null) e.setStatus("ENABLED");
        if (e.getRateLimit() == null) e.setRateLimit(0);
    }

    /** 密钥哈希是「只在创建时写一次」的列：更新请求不带它时保留原值，不要被 null 覆盖。 */
    @Override
    protected void beforeUpdate(OpenapiApp e, OpenapiApp current) {
        if (e.getAppSecretHash() == null) e.setAppSecretHash(current.getAppSecretHash());
        if (e.getAppKey() == null) e.setAppKey(current.getAppKey());
    }

    @Override
    protected OpenApiApp toVO(OpenapiApp e) {
        return new OpenApiApp(e.getAppNo(), e.getName(), e.getAppKey(), e.getRateLimit(),
                e.getStatus(), SysCtx.fmt(e.getCreatedAt()),
                mask(e.getAppSecretHash()), SysCtx.fmt(e.getSecretResetAt()));
    }

    /**
     * 密钥掩码。**只根据哈希是否存在给出「有/无」的形态，不泄露哈希本身的任何片段** ——
     * 哈希前缀同样是可用于离线爆破的信息，何况前端只需要知道「配没配」。
     */
    private static String mask(String hash) {
        return hash == null || hash.isBlank() ? "" : "••••••••";
    }

    /**
     * 重置密钥：服务端生成新明文 → 只落哈希 + 重置时间 → **明文不入库、不出参**。
     *
     * <p>为什么不把明文回给前端：运营端是浏览器，明文一旦进响应就会进日志、进 devtools、
     * 进截图。正确形状是调用方从 KMS/vault 自取（`app_secret_hash` 的库注释已定此口径）。
     * 因此本方法只回掩码与时间 —— 「重置成功」这件事本身就是它要传达的全部信息。
     */
    @Transactional
    public OpenApiApp resetSecret(String appNo) {
        OpenapiApp e = mapper.selectOne(new LambdaQueryWrapper<OpenapiApp>()
                .eq(OpenapiApp::getAppNo, appNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("应用不存在: " + appNo);
        // 明文只在本方法栈内存在，随即被哈希取代；不记日志、不回参。
        String plain = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        e.setAppSecretHash(sha256(plain));
        e.setSecretResetAt(LocalDateTime.now(ZoneOffset.UTC));
        mapper.updateById(e);
        return toVO(e);   // 回完整行：前端契约是 OpenApiApp，拿到即可原地刷新该行
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);   // JDK 保证有，不可能走到
        }
    }
}
