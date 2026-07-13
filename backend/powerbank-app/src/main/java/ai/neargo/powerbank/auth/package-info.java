/**
 * <b>权限基础设施层（infra）</b> —— 未来抽为脚手架 starter jar {@code powerbank-security-starter}
 * （[ADR-015](../../../../../../../../docs/technical/ADR/ADR-015-权限模块化与打包方式.md)）。
 *
 * <p>内容：{@code TokenStore} SPI + 会话数据；{@code SecurityUtils}/{@code StaffContext}/{@code ConsumerContext}；
 * {@code LoginUser}/{@code Realm}/{@code ScopeDim}/{@code DataScope}；{@code PermChecker(@perm)}/{@code PermVersion}；
 * 数据权限 {@code DataScopeHandler}/{@code DataScopeTableRegistry}/{@code DataScopeContext}；
 * 两认证过滤器；主体重建抽象 {@code PrincipalRefresher}。
 *
 * <p><b>分层铁律</b>：本包**只被依赖，不反向依赖** IAM 业务（{@code platform.iam}）或任何业务域。
 * 口径 B 的会话重建经 {@code PrincipalRefresher} 接口（DIP）由 IAM 注入实现，故 infra 不依赖 iam。
 * 边界干净 ⇒ 未来抽 starter jar = 纯搬迁。
 */
package ai.neargo.powerbank.auth;
