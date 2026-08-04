package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.LoginSetting;
import ai.neargo.sharehub.platform.sys.entity.SysLoginSetting;
import ai.neargo.sharehub.platform.sys.mapper.SysLoginSettingMapper;
import ai.neargo.sharehub.platform.sys.service.LoginSettingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 登录设置实现。
 *
 * <p>两处与基类不同：
 * <ol>
 *   <li>{@code selectByKey} 带 tenantId —— UK 是 {@code (tenant_id, country)}；</li>
 *   <li>建与改都过 {@link #requireAtLeastOneMethod}：<b>四种登录方式不能全关</b>。
 *       这不是「校验一下更友好」，是**不可逆事故防线**——全关之后该国用户连登录页都进不去，
 *       只能改库救回来。</li>
 * </ol>
 */
@Service
public class LoginSettingServiceImpl extends AbstractCrudService<SysLoginSetting, LoginSetting>
        implements LoginSettingService {

    /** 默认行：端上查不到本国配置时回落它。 */
    private static final String DEFAULT_COUNTRY = "*";

    public LoginSettingServiceImpl(SysLoginSettingMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "country";
    }

    @Override
    protected String keyOf(SysLoginSetting e) {
        return e.getCountry();
    }

    @Override
    protected void setKey(SysLoginSetting e, String no) {
        e.setCountry(no);
    }

    /** 复合 UK {@code (tenant_id, country)}。 */
    @Override
    protected SysLoginSetting selectByKey(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<SysLoginSetting>()
                .eq("country", no)
                .eq("tenant_id", SysCtx.tenantId())
                .last("limit 1"));
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"country", "country_name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"country"};
    }

    @Override
    protected String orderColumn() {
        return "country";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(SysLoginSetting e) {
        if (e.getOtpExpireSec() == null) e.setOtpExpireSec(300);
        if (e.getOtpDailyLimit() == null) e.setOtpDailyLimit(10);
        if (e.getForceRealName() == null) e.setForceRealName(0);
        requireAtLeastOneMethod(e);
    }

    @Override
    protected void beforeUpdate(SysLoginSetting e, SysLoginSetting current) {
        requireAtLeastOneMethod(e);
    }

    @Override
    public LoginSetting effective(String country) {
        LoginSetting own = get(country);
        return own != null ? own : get(DEFAULT_COUNTRY);
    }

    /**
     * 至少保留一种登录方式。四个开关全为 0/null 即拒绝。
     *
     * @throws IllegalArgumentException 全关（全局异常处理器映射为 400）
     */
    private static void requireAtLeastOneMethod(SysLoginSetting e) {
        boolean any = on(e.getOtpEnabled()) || on(e.getPasswordEnabled())
                || on(e.getAppleEnabled()) || on(e.getGoogleEnabled());
        if (!any) {
            throw new IllegalArgumentException(
                    "登录设置至少需保留一种登录方式（验证码/密码/Apple/Google），国家=" + e.getCountry());
        }
    }

    private static boolean on(Integer v) {
        return v != null && v == 1;
    }

    @Override
    protected LoginSetting toVO(SysLoginSetting e) {
        return new LoginSetting(e.getCountry(), e.getCountryName(),
                SysCtx.bool(e.getOtpEnabled()), SysCtx.bool(e.getPasswordEnabled()),
                SysCtx.bool(e.getAppleEnabled()), SysCtx.bool(e.getGoogleEnabled()),
                e.getOtpExpireSec(), e.getOtpDailyLimit(), SysCtx.bool(e.getForceRealName()));
    }
}
