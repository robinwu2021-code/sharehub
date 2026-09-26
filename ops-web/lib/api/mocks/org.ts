// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
import * as db from "../../mock/db";
import * as menuDb from "../../mock/db/menu";
import type { OrgApi } from "../contracts/org";
import type { PageQ, ArchiveQ , ReportQ } from "../query";
import { wait } from "./_wait";

export const orgMock: OrgApi = {
  listEmployees: (q: PageQ = {}) => wait(db.listEmployees(q)),
  listRoles: (q: ArchiveQ = {}) => wait(db.listRoles(q)),
  listAudits: (q: PageQ = {}) => wait(db.paginate(db.audits, q.page, q.size, (a) => db.kwHit(q.keyword, a.actor, a.action, a.target))),

  // 员工扩展
  listDepartments: (q: PageQ = {}) => wait(db.listDepartments(q)),
  listStaffPerformance: (q: ReportQ = {}) => wait(db.listStaffPerformance(q)),
  saveDepartment: (x) => wait(db.saveDepartment(x), 350),
  saveRoleRow: (x) => wait(db.saveRoleRow(x), 350),
  saveEmployee: (x) => wait(db.saveEmployee(x), 350),
  resetEmployeeCredential: (no) => wait(db.resetEmployeeCredential(no), 350),
  getDataScope: (subjectType, subjectNo) => wait(db.getDataScope(subjectType, subjectNo)),
  saveDataScope: (subjectType, subjectNo, scope, values) =>
    wait(db.saveDataScope(subjectType, subjectNo, scope, values), 350),

  // 功能权限（S6）
  // 全量（连停用的也在）+ 改。两者都走 mock db —— 改动要能读回，
  // 且两道闸（perm 在目录里 / 不能藏掉「员工与权限」）在 mock 层同样强制。
  listAllMenus: async () => wait(await menuDb.allMenus()),
  updateMenu: async (menuNo, patch) =>
    wait(await menuDb.updateMenu(menuNo, patch, db.permissions.map((p) => p.code)), 350),
  listPermissions: () => wait(db.permissions),
  listRolePermissions: (roleNo) => wait(db.listRolePermissions(roleNo)),
  saveRolePermissions: (roleNo, perms) => wait(db.saveRolePermissions(roleNo, perms), 350),

  getAuditDetail: (id) => wait(db.getAuditDetail(id)),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveRole: async (no) => wait(db.archiveRole(no), 350),
  unarchiveRole: async (no) => wait(db.unarchiveRole(no), 350),
};
