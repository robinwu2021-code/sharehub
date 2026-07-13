/**
 * <b>IAM 业务模块</b> —— 角色/权限码/菜单/数据范围的**动态管理**域。未来抽为业务模块 jar
 * {@code powerbank-iam}（[ADR-015](../../../../../../../../../docs/technical/ADR/ADR-015-权限模块化与打包方式.md)），
 * 与 {@code trade}/{@code ops}/{@code user} 平级装进 app-modulith（单可部署，不独立服务）。
 *
 * <p>内容：{@code iam_*} 实体/Mapper；{@code PermissionService}（角色→码+数据范围，实现 infra 的
 * {@code PrincipalRefresher}）；{@code MenuService}（动态菜单）；{@code IamAdminController}（{@code /api/platform/iam/*} 后台配置）；
 * {@code IamSeeder}（等价种子）；{@code AuthController}（登录/menus/permissions）；{@code RolePerms}（种子源）。
 *
 * <p><b>分层</b>：依赖 infra（{@code auth}），**不依赖**任何业务域（trade/ops/…）。表在 {@code pb_core.iam_*}。
 * 边界干净 ⇒ 未来抽 {@code powerbank-iam} jar = 纯搬迁（需 infra starter 先行，见 ADR-015 迁移路径）。
 */
package ai.neargo.powerbank.platform.iam;
