// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
import * as db from "../../mock/db";
import type { OrgApi } from "../contracts/org";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const orgMock: OrgApi = {
  listEmployees: (q: PageQ = {}) => wait(db.listEmployees(q)),
  listRoles: () => wait(db.roles),
  listAudits: (q: PageQ = {}) => wait(db.paginate(db.audits, q.page, q.size, (a) => db.kwHit(q.keyword, a.actor, a.action, a.target))),

  // 员工扩展
  listDepartments: (q: PageQ = {}) => wait(db.listDepartments(q)),
  listStaffPerformance: (q: PageQ = {}) => wait(db.listStaffPerformance(q)),
  saveDepartment: (x) => wait(db.saveDepartment(x), 350),
  saveRoleRow: (x) => wait(db.saveRoleRow(x), 350),
  saveEmployee: (x) => wait(db.saveEmployee(x), 350),
};
