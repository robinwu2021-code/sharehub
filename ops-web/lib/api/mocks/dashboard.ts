// 覆盖范围：认证登录 + 工作台首页统计。
import * as db from "../../mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";
import { permsOf } from "../../permissions";
import { fail } from "../../biz-error";
import { currentAuth, type Role } from "../../auth";
import * as menuDb from "../../mock/db/menu";
import { opsFlowMetrics } from "../../mock/db/ops-flow-metrics";

/** dev-mode 固定验证码，与后端 OtpService.DEV_MASTER 同值 —— 两边不一致就「收到码了但验不过」。 */
const DEV_OTP = "000000";
/**
 * 一个人名下的主体。刻意给两个：只有多主体才试得出切换器，单主体的话那个入口永远不显示。
 *
 * **名字取自 agents 种子而不是另编一套**：提现、分润、结算都按 agentNo 关联，
 * 而界面显示的是名字。两边各编一套的话，代理登录后会看到切换器写着「示例代理商甲」、
 * 自己的提现单却署名「North Hub」—— 看上去像筛错了数据。
 */
const agentOperators = () => db.agents.slice(0, 2).map((a, i) => ({
  operatorNo: a.agentNo, name: a.name, isOwner: i === 0, isPrimary: i === 0,
}));

export const dashboardMock: DashboardApi = {
  /*
   * mock 登录：用**用户名**当角色开关（admin/ops/cs/finance/bd/viewer/agent），
   * 真后端下用户名与角色无关 —— 这里只是离线开发时挑身份的手段。
   * perms 按 BACKEND_ROLE_PERMS 展开：**mock 也走「判权读 perms」那条路**，
   * 否则 mock 与真后端两套判权逻辑，mock 下测不出权限问题。
   */
  login: (realm, identifier, _password, otp) => {
    const guess = identifier.split("@")[0].toUpperCase();
    const role = (realm === "AGENT" ? "AGENT"
      : (["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER"] as const).find((r) => r === guess) ?? "ADMIN");
    // 代理端必须带验证码 —— 后端就是这么判的（口令登录压根没有凭据存储）。
    // mock 放行而后端拒绝的话，离线开发一路顺，切后端当场登不进去。
    if (realm === "AGENT" && !otp?.trim()) fail("请输入验证码", "Verification code required", "رمز التحقق مطلوب");
    // 两种失败说同一句话：区分开就等于送出一个账号枚举接口
    if (realm === "AGENT" && otp !== DEV_OTP) fail("手机号或验证码不正确", "Incorrect phone number or code", "رقم الهاتف أو الرمز غير صحيح");
    const operators = realm === "AGENT" ? agentOperators() : undefined;
    return wait({
      token: `mock-${role}`, subjectNo: identifier, username: identifier, role,
      perms: permsOf(role as Role),
      operators, currentOperatorNo: operators?.[0].operatorNo,
    });
  },
  // 查无此号也返回 ok：见契约注释（否则是一台手机号枚举器）。mock 一律回显固定码
  sendLoginOtp: (_phone) => wait({ ok: true, code: DEV_OTP }),
  listOperators: () => wait(agentOperators()),
  switchOperator: (agentNo) => {
    const all = agentOperators();
    const hit = all.find((o) => o.operatorNo === agentNo);
    // 不说「该主体不存在」——那会泄露别家主体编号的存在性
    if (!hit) fail("你不属于该运营主体，无法切换", "You do not belong to this operator", "أنت لا تنتمي إلى هذا المشغل");
    return wait({
      token: `mock-AGENT-${agentNo}`, subjectNo: "PR0001", username: hit.name, role: "AGENT" as Role,
      perms: permsOf("AGENT" as Role),
      operators: all, currentOperatorNo: agentNo,
    });
  },
  /*
   * mock 的 /me：离线开发没有后端可重算权限，只能按**当前 store 里的角色**展开。
   *
   * 于是 mock 下它天然是恒等的 —— **测不出「权限变了」那条路径**。
   * 这是 mock 的边界，不是实现偷懒：真正的收敛验证在后端
   * `PermsRefreshWithoutReloginTest`，前端那一半由 perms-sync.test.ts 打桩验。
   */
  me: () => {
    const { token, username, role } = currentAuth() ?? { token: "", username: "", role: "" };
    if (!token) return wait({ authenticated: false });
    return wait({
      authenticated: true, username, role,
      perms: permsOf(role as Role | ""),
    });
  },
  /*
   * 我看得到的那棵。与 listAllMenus 走**同一个 mock db** ——
   * 此前这里和 mocks/org 各建了一棵，改了菜单只有一边变。
   */
  getMenus: async () => {
    const a = currentAuth();
    return wait(await menuDb.visibleMenus(a?.perms ?? [], a?.role ?? ""));
  },
  // mock 没有服务端会话可吊销，但**必须存在** —— 契约测试要求 mock 与 http 同形，
  // 缺一个方法会让 mock 模式在点登出时直接 TypeError。
  logout: () => wait(undefined as void),
  getDashboard: () => wait(db.dashboard),
  getOpsFlowMetrics: (q) => wait(opsFlowMetrics(q ?? {})),
};
