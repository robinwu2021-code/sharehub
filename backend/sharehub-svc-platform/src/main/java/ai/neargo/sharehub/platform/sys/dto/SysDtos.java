package ai.neargo.sharehub.platform.sys.dto;

import java.math.BigDecimal;

/**
 * platform/sys 子域出参 VO。字段镜像 ops-web {@code lib/types/system.ts} 的同名 interface。
 *
 * <p>命名上有两处刻意的错位，别「顺手改齐」：
 * <ul>
 *   <li>{@code SysParamEntry} vs 实体 {@code SysParam} —— 同名会让控制器无法同时 import 二者；</li>
 *   <li>{@code OpenApiApp}(VO) vs 实体 {@code OpenapiApp} —— 表名是 {@code openapi_app}，
 *       前端字段名是 {@code OpenApiApp}，两边各自保留惯用拼写。</li>
 * </ul>
 */
public final class SysDtos {

    private SysDtos() {
    }

    // ——————————————————— 系统参数 ———————————————————

    /** 系统参数行，镜像前端 {@code SysParam}。 */
    public record SysParamEntry(String paramKey, String label, String value,
                                String groupName, String updatedAt) {
    }

    // ——————————————————— 业务规则（三分区单例）———————————————————

    /**
     * 提现规则 —— **全站提现手续费口径的唯一来源**。
     *
     * @param feeRate 费率 0~1（注意与税率的百分数口径相反，前端 {@code WithdrawRule.feeRate} 同为 0~1）
     * @param feeCap  手续费封顶（按 {@link BizRules#currency}）
     */
    public record WithdrawRule(BigDecimal minAmount, BigDecimal feeRate, BigDecimal feeCap,
                               Integer settleDays, BigDecimal dailyLimit, Boolean needApproval) {
    }

    /** 预约规则。 */
    public record ReservationRule(Integer maxDurationMin, Integer advanceHours,
                                  BigDecimal holdFeePerMin, Integer maxConcurrent) {
    }

    /** 计费默认值（具体套餐仍以 price_plan 为准，这里只是兜底）。 */
    public record BillingDefaultRule(Integer freeMinutes, Integer unitMinutes, BigDecimal capDaily,
                                     BigDecimal buyoutPrice, Integer overdueHours) {
    }

    /**
     * 业务规则聚合视图（读）/ 分区保存入参（写）。
     *
     * <p><b>写是 {@code Partial} 语义</b>：页面三个分区各有独立保存按钮，
     * 一次 POST 通常只带一个非空分区，服务端**只合并传入的分区**，
     * 其余两个原样保留。三个分区在库里是 {@code sys_biz_rule} 的三行，不是一行三列。
     */
    public record BizRules(WithdrawRule withdraw, ReservationRule reservation,
                           BillingDefaultRule billing, String currency, String updatedAt) {
    }

    // ——————————————————— 登录设置 ———————————————————

    /** 登录设置行，镜像前端 {@code LoginSetting}。{@code country='*'} 为默认档。 */
    public record LoginSetting(String country, String countryName,
                               Boolean otpEnabled, Boolean passwordEnabled,
                               Boolean appleEnabled, Boolean googleEnabled,
                               Integer otpExpireSec, Integer otpDailyLimit, Boolean forceRealName) {
    }

    // ——————————————————— 应用版本 ———————————————————

    /** 应用版本行，镜像前端 {@code AppVersion}。{@code rolloutPercent} 为 0..100。 */
    public record AppVersion(String versionId, String versionNo, String platform, Integer buildNo,
                             String releaseNote, String releaseNoteEn, String releaseNoteAr,
                             Boolean forceUpdate, String minSupported, BigDecimal rolloutPercent,
                             String downloadUrl, String status, String releasedAt) {
    }

    /**
     * C端版本检查结果（{@code GET /mp/app/version}）：端上只关心「要不要升、升到哪、怎么升」。
     *
     * @param hasUpdate 无在架版本时为 false，其余字段可为空
     */
    public record AppVersionCheck(Boolean hasUpdate, String versionNo, Integer buildNo,
                                  Boolean forceUpdate, String minSupported,
                                  String releaseNote, String downloadUrl) {
    }

    // ——————————————————— 税率 / 开放平台 ———————————————————

    /** 税率与发票行，镜像前端 {@code TaxSetting}。{@code ratePercent} 为 0..100。 */
    public record TaxSetting(String country, String countryName, String taxName, BigDecimal ratePercent,
                             String trn, String invoiceTitle, Boolean includedInPrice, String effectiveFrom) {
    }

    /** OpenAPI 应用行，镜像前端 {@code OpenApiApp}。**不含 appSecretHash**（密钥不出参）。 */
    /**
     * OpenAPI 应用出参。
     *
     * <p><b>只出掩码与重置时间，永不出密钥明文或哈希</b>：`app_secret_hash` 的库注释已经
     * 定了口径（明文落 KMS/vault、不入库、不出参）。掩码由服务端算，前端拿不到可还原的信息。
     */
    /**
     * 开放平台应用**写入参**（白名单）。
     *
     * <p>不声明 {@code appSecretHash} 与 {@code secretResetAt} —— 它们**只由
     * {@code POST /api/platform/openapi-apps/{appNo}/reset-secret} 维护**
     * （那里生成明文、取 SHA-256、落 secretResetAt，明文只在方法栈内存在）。
     *
     * <p>此前这两个端点收的是实体，而 beforeUpdate 的保护是
     * {@code if (e.getAppSecretHash() == null) e.setAppSecretHash(current...)} ——
     * **只在客户端「不传」时生效**。注释写的是「更新请求不带它时保留原值」，
     * 作者的意图是「别被清空」，不是「不许被设置」。于是往编辑端点传
     * {@code {"appSecretHash":"<自己算的哈希>"}} 就把该应用的密钥换成了已知值，
     * 等于拿到它的全部 API 权限 —— 而这个端点只需要 system:openapi:update。
     *
     * <p>{@code appKey} 保留：运营端表单里本来就有它（后端从不生成），是正当入参。
     */
    public record OpenApiAppReq(String appNo, String name, String appKey,
                                String scopes, Integer rateLimit, String status) {
        /** 映射到实体。**appSecretHash / secretResetAt 有意不设**（见类注释）。 */
        public ai.neargo.sharehub.platform.sys.entity.OpenapiApp toEntity() {
            var e = new ai.neargo.sharehub.platform.sys.entity.OpenapiApp();
            e.setAppNo(appNo);
            e.setName(name);
            e.setAppKey(appKey);
            e.setScopes(scopes);
            e.setRateLimit(rateLimit);
            e.setStatus(status);
            return e;
        }
    }

    public record OpenApiApp(String appNo, String name, String appKey, Integer rateLimit,
                             String status, String createdAt,
                             String appSecretMasked, String secretResetAt) {
    }

}
