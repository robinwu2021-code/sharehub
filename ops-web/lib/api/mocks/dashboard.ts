// 覆盖范围：认证登录 + 工作台首页统计。
import * as db from "../../mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";
import { permsOf } from "../../permissions";
import { fail } from "../../biz-error";
import type { Role } from "../../auth";

/** dev-mode 固定验证码，与后端 OtpService.DEV_MASTER 同值 —— 两边不一致就「收到码了但验不过」。 */
const DEV_OTP = "000000";
/** 一个人名下的主体。刻意给两个：只有多主体才试得出切换器，单主体的话那个入口永远不显示。 */
const agentOperators = () => [
  { operatorNo: "AG001", name: "示例代理商甲", isOwner: true, isPrimary: true },
  { operatorNo: "AG002", name: "示例代理商乙", isOwner: false, isPrimary: false },
];

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
  // mock 没有服务端会话可吊销，但**必须存在** —— 契约测试要求 mock 与 http 同形，
  // 缺一个方法会让 mock 模式在点登出时直接 TypeError。
  logout: () => wait(undefined as void),
  getDashboard: () => wait(db.dashboard),
};
