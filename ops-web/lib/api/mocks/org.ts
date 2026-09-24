// 覆盖范围：组织与权限 —— 员工、角色、审计日志、部门、员工绩效。
import * as db from "../../mock/db";
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
  getDataScope: (subjectType, subjectNo) => wait(db.getDataScope(subjectType, subjectNo)),
  saveDataScope: (subjectType, subjectNo, scope, values) =>
    wait(db.saveDataScope(subjectType, subjectNo, scope, values), 350),

  // 功能权限（S6）
  /*
   * mock 的全量菜单：直接由本地 NAV 反推，不另编一套 ——
   * 编出来的那份和 nav.ts 一漂，离线开发时预览出来的菜单就和线上不是一回事。
   * 这里**不过滤**（全量），与真后端 /api/platform/iam/menus 的语义一致。
   */
  listAllMenus: async () => {
    const { NAV } = await import("../../nav");
    return wait(NAV.map((s, i) => ({
      menuNo: `M_${s.key}`, parentNo: null, name: s.label, nameEn: null, nameAr: null,
      type: "MENU" as const, path: s.href, icon: s.icon ?? null, group: null, sort: i + 1,
      perm: s.perm ?? null, phase: s.phase ?? 1, ready: false, module: s.module ?? null,
      modules: s.modules ?? [], match: s.match ?? [], pinBottom: !!s.pinBottom,
      portalFor: (s.portalFor ?? []) as string[],
      children: (s.children ?? []).map((l, k) => ({
        menuNo: `M_${s.key}__${k + 1}`, parentNo: `M_${s.key}`, name: l.label,
        nameEn: null, nameAr: null, type: "ITEM" as const, path: l.href, icon: null,
        group: l.group ?? null, sort: k + 1, perm: l.perm ?? null, phase: l.phase ?? 1,
        ready: !!l.ready, module: null, modules: [], match: [], pinBottom: false,
        portalFor: [], children: [],
      })),
    })));
  },
  listPermissions: () => wait(db.permissions),
  listRolePermissions: (roleNo) => wait(db.listRolePermissions(roleNo)),
  saveRolePermissions: (roleNo, perms) => wait(db.saveRolePermissions(roleNo, perms), 350),

  getAuditDetail: (id) => wait(db.getAuditDetail(id)),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveRole: async (no) => wait(db.archiveRole(no), 350),
  unarchiveRole: async (no) => wait(db.unarchiveRole(no), 350),
};
