package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.MarketCountry;
import ai.neargo.sharehub.platform.md.entity.MdMarketCountry;
import ai.neargo.sharehub.platform.md.service.MarketService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.AppVersion;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.BizRules;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.LoginSetting;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.OpenApiAppReq;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.OpenApiApp;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.TaxSetting;
import ai.neargo.sharehub.platform.sys.entity.OpenapiApp;
import ai.neargo.sharehub.platform.sys.entity.SysAppVersion;
import ai.neargo.sharehub.platform.sys.entity.SysLoginSetting;
import ai.neargo.sharehub.platform.sys.entity.SysTaxSetting;
import ai.neargo.sharehub.platform.sys.service.AppVersionService;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import ai.neargo.sharehub.platform.sys.service.LoginSettingService;
import ai.neargo.sharehub.platform.sys.service.OpenApiAppService;
import ai.neargo.sharehub.platform.sys.service.TaxSettingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统设置 · 业务规则（[api/README §7.4]）+ 开放与市场（§7.6）。
 *
 * <p><b>本控制器没有类级 {@code @RequestMapping}</b>：路径逐个写在方法上。
 * （§7.7 租户的四个 {@code /internal/platform/tenants**} 端点已随 ADR-026 退役。）
 *
 * <p>三条容易写反的语义，改之前先看 service 注释：
 * <ul>
 *   <li>{@code POST /biz-rules} 是 <b>分区合并</b>（Partial），不是整体覆盖；</li>
 *   <li>{@code POST /app-versions/{versionId}/rollback} 是 <b>软回滚</b>（改状态，留记录）；</li>
 *   <li>{@code POST /login-settings} 会因「四种登录方式全关」被拒 400。</li>
 * </ul>
 */
@RestController
public class SysSettingController {

    private final BizRuleService bizRules;
    private final LoginSettingService loginSettings;
    private final AppVersionService appVersions;
    private final TaxSettingService taxSettings;
    private final MarketService markets;
    private final OpenApiAppService openApiApps;

    public SysSettingController(BizRuleService bizRules, LoginSettingService loginSettings,
                                AppVersionService appVersions, TaxSettingService taxSettings,
                                MarketService markets, OpenApiAppService openApiApps) {
        this.bizRules = bizRules;
        this.loginSettings = loginSettings;
        this.appVersions = appVersions;
        this.taxSettings = taxSettings;
        this.markets = markets;
        this.openApiApps = openApiApps;
    }

    // ——————————————— §7.4 业务规则（单例，三分区）———————————————

    /** 读全量三分区；缺失分区由 service 兜默认值，前端不必再写一遍默认。 */
    @GetMapping("/api/platform/biz-rules")
    @PreAuthorize("@perm.can('system:biz_rule:read')")
    public BizRules bizRules() {
        return bizRules.get();
    }

    /**
     * 分区保存：body 是 {@code Partial<BizRules>}，页面三个保存按钮 → 三次独立 POST。
     * <b>只合并传入的分区</b>，其余两个原样保留。
     */
    @PostMapping("/api/platform/biz-rules")
    @PreAuthorize("@perm.can('system:biz_rule:update')")
    public BizRules saveBizRules(@RequestBody BizRules body) {
        return bizRules.save(body);
    }

    // ——————————————— §7.4 登录设置（按国家）———————————————

    @GetMapping("/api/platform/login-settings")
    @PreAuthorize("@perm.can('system:login_setting:read')")
    public PageResult<LoginSetting> loginSettings(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String country) {
        return loginSettings.page(page, size, keyword, Map.of("country", nz(country)));
    }

    @PostMapping("/api/platform/login-settings")
    @PreAuthorize("@perm.can('system:login_setting:update')")
    public LoginSetting createLoginSetting(@RequestBody SysLoginSetting body) {
        return loginSettings.save(body); // country 是自然键（'*' = 默认行），新建必须给
    }

    @PostMapping("/api/platform/login-settings/{country}")
    @PreAuthorize("@perm.can('system:login_setting:update')")
    public LoginSetting updateLoginSetting(@PathVariable String country, @RequestBody SysLoginSetting body) {
        body.setCountry(country);
        return loginSettings.save(body);
    }

    // ——————————————— §7.4 应用版本 ———————————————

    @GetMapping("/api/platform/app-versions")
    @PreAuthorize("@perm.can('system:app_version:read')")
    public PageResult<AppVersion> appVersions(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String platform,
                                              @RequestParam(required = false) String status) {
        return appVersions.page(page, size, keyword,
                Map.of("platform", nz(platform), "status", nz(status)));
    }

    @PostMapping("/api/platform/app-versions")
    // 清单 §系统「应用版本 查/发布」只声明 read 与 release，**没有 :update 这个码**。
    // 三个写端点（建版本/改版本/回滚）都属"发布"，统一判 :release。
    // （两码当前都只有 ADMIN 持有，访问面不变。）
    @PreAuthorize("@perm.can('system:app_version:release')")
    public AppVersion createAppVersion(@RequestBody SysDtos.AppVersionReq body) {
        return appVersions.save(body.toEntity()); // versionId 由 service 按 平台-版本号 拼出
    }

    @PostMapping("/api/platform/app-versions/{versionId}")
    @PreAuthorize("@perm.can('system:app_version:release')")
    public AppVersion updateAppVersion(@PathVariable String versionId,
                                       @RequestBody SysDtos.AppVersionReq body) {
        SysAppVersion e = body.toEntity();
        e.setVersionId(versionId);
        return appVersions.save(e);
    }

    /** 软回滚：{@code status=ROLLBACK} + {@code rolloutPercent=0}，**记录保留**。 */
    @PostMapping("/api/platform/app-versions/{versionId}/rollback")
    @PreAuthorize("@perm.can('system:app_version:release')")
    public AppVersion rollbackAppVersion(@PathVariable String versionId) {
        return appVersions.rollback(versionId);
    }

    // ——————————————— §7.6 税率与发票 ———————————————

    @GetMapping("/api/platform/tax-settings")
    @PreAuthorize("@perm.can('system:tax:read')")
    public PageResult<TaxSetting> taxSettings(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String country) {
        return taxSettings.page(page, size, keyword, Map.of("country", nz(country)));
    }

    @PostMapping("/api/platform/tax-settings")
    @PreAuthorize("@perm.can('system:tax:update')")
    public TaxSetting createTaxSetting(@RequestBody SysTaxSetting body) {
        return taxSettings.save(body);
    }

    @PostMapping("/api/platform/tax-settings/{country}")
    @PreAuthorize("@perm.can('system:tax:update')")
    public TaxSetting updateTaxSetting(@PathVariable String country, @RequestBody SysTaxSetting body) {
        body.setCountry(country);
        return taxSettings.save(body);
    }

    // ——————————————— §7.6 多国家市场 ———————————————

    @GetMapping("/api/platform/markets")
    @PreAuthorize("@perm.can('system:market:read')")
    public PageResult<MarketCountry> markets(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String currency) {
        return markets.page(page, size, keyword,
                Map.of("status", nz(status), "currency", nz(currency)));
    }

    @PostMapping("/api/platform/markets")
    @PreAuthorize("@perm.can('system:market:update')")
    public MarketCountry createMarket(@RequestBody MdMarketCountry body) {
        return markets.save(body);
    }

    @PostMapping("/api/platform/markets/{countryCode}")
    @PreAuthorize("@perm.can('system:market:update')")
    public MarketCountry updateMarket(@PathVariable String countryCode, @RequestBody MdMarketCountry body) {
        body.setCountryCode(countryCode);
        return markets.save(body);
    }

    // ——————————————— §7.6 OpenAPI 应用 ———————————————

    @GetMapping("/api/platform/openapi-apps")
    @PreAuthorize("@perm.can('system:openapi:read')")
    public PageResult<OpenApiApp> openApiApps(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return openApiApps.page(page, size, keyword, Map.of("status", nz(status)));
    }

    @PostMapping("/api/platform/openapi-apps")
    @PreAuthorize("@perm.can('system:openapi:update')")
    public OpenApiApp createOpenApiApp(@RequestBody OpenApiAppReq body) {
        return openApiApps.save(body.toEntity());
    }

    @PostMapping("/api/platform/openapi-apps/{appNo}")
    @PreAuthorize("@perm.can('system:openapi:update')")
    public OpenApiApp updateOpenApiApp(@PathVariable String appNo, @RequestBody OpenApiAppReq body) {
        OpenapiApp e = body.toEntity();
        e.setAppNo(appNo);
        return openApiApps.save(e);
    }

    /**
     * 重置 OpenAPI 应用密钥。
     *
     * <p>运营端早已上线这个动作（硬确认需手输 appNo），但后端一直没有端点。
     * 服务端生成新明文 → 只落哈希 + 重置时间 → **明文不入库、不出参**
     * （`openapi_app.app_secret_hash` 的库注释定的口径：明文归 KMS/vault）。
     * 响应回完整行但密钥只出掩码：把明文回给浏览器等于让它进日志、devtools 和截图。
     *
     * <p>权限用 {@code system:openapi:update} 而非 {@code :read} —— 这一下会**作废调用方
     * 手上的旧密钥**，是破坏性操作。
     */
    @PostMapping("/api/platform/openapi-apps/{appNo}/reset-secret")
    @PreAuthorize("@perm.can('system:openapi:update')")
    public OpenApiApp resetOpenApiAppSecret(@PathVariable String appNo) {
        return openApiApps.resetSecret(appNo);
    }

    // §7.7 租户端点已随 ADR-026 退役（2026-09-23）。
    // 产品层没有租户概念，这四个口子无 UI、无前端调用、无测试覆盖 —— 控制器自己的注释
    // 把它们叫做「休眠口子」。留着的代价不是运行时开销，而是**下一个读代码的人会
    // 合理地认为这个系统支持多租户**，进而照着它设计新功能。
    // tenant/tenant_config 两张表保留（同 tenant_id 降级为历史列的处置）。

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
