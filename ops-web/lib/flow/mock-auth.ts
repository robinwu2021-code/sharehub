// mock 层的判权 —— 模拟 OpsKit 服务端按操作人权限做的两件事：
//   1. 动作列表 actions[] 只含当前操作人能走的（前端不再判一遍）；
//   2. 写接口缺权限返回 403。
// 权限来源 = 登录下发的 perms（ShareHub 持有、OpsKit 按 21/07 P3 映射后判的同一批 flow:* 码）。
//
// ⚠️ 没有「空即放行」：perms 为空一律无权限（权限系统最常见的漏洞，perm-map.test.ts 专门拦过）。
//    单测用 setMockOperatorPerms 显式给权限。
import { ApiError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth";
import { can } from "@/lib/permissions";

let override: string[] | null = null;

/** 单测用：显式指定 mock 操作人的权限（null = 回到读登录态） */
export function setMockOperatorPerms(perms: string[] | null) {
  override = perms;
}

function perms(): string[] {
  return override ?? useAuth.getState().perms ?? [];
}

export function hasPerm(code: string): boolean {
  return can(perms(), code);
}

/** 缺权限：403（与服务端同语义），说清缺哪个码 */
export function requirePerm(code: string): void {
  if (!hasPerm(code)) throw new ApiError(403, `当前角色没有这项权限（${code}）`);
}
