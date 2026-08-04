/**
 * 组织域（{@code iam_employee} / {@code iam_dept} / {@code iam_employee_role} /
 * {@code iam_staff_perf} / {@code iam_audit_log}）—— [db-design §2.2]。
 *
 * <p><b>与 {@code platform.iam} 的分工</b>：{@code iam} 包管**权限本身**
 * （角色、权限码、角色权限、数据范围、菜单）；本包管**组织实体**（人、部门、绩效、审计）。
 * 两者共用 {@code iam_} 表前缀是因为同属 D1 认证权限子域，但生命周期不同：
 * 权限配置由管理员实时改、要即时生效；组织数据随人事变动而变、需留痕。
 *
 * <p>数据权限（{@code iam_data_scope}）落在 {@code iam} 包，本包只提供组织页面保存它的写入口
 * （{@code DataScopeService}，补 G7 缺口）。
 */
package ai.neargo.sharehub.platform.org;
