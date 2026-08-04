package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.LoginSetting;
import ai.neargo.sharehub.platform.sys.entity.SysLoginSetting;

/**
 * 登录设置（按国家）。形态是 CRUD，但带一条**硬校验**：
 * 保存时 otp/password/apple/google **至少留一种**为开，否则拒绝
 * —— 全关会把该国用户永久锁在门外。
 *
 * <p>{@code country='*'} 是默认行，端上查不到本国配置时回落它。
 */
public interface LoginSettingService extends CrudService<SysLoginSetting, LoginSetting> {

    /** 端上取生效配置：先查 {@code country}，无则回落默认行 {@code '*'}；都没有返回 {@code null}。 */
    LoginSetting effective(String country);
}
