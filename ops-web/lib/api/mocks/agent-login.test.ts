import { describe, it, expect } from "vitest";
import { dashboardMock } from "./dashboard";

/**
 * 代理端实名登录在 mock 侧的回归（④⑤）。
 *
 * 钉的是**与后端的一致性**：mock 放行而后端拒绝，离线开发一路顺、切后端当场登不进去。
 * 这类分叉最贵 —— 它要到联调那天才暴露，而那时两边都以为对方错了。
 */
describe("④ 商家登录：验证码或口令，二者必居其一", () => {
  // 校验是**同步抛**的（与 db 层各 mock 同约定），所以断言包一层函数而不是用 rejects
  it("验证码与口令都没给进不来", () => {
    expect(() => dashboardMock.login("AGENT", "+971501234567", "")).toThrow(/验证码或口令/);
  });

  it("口令登录（开通时发的一次性口令，2026-10-28 起）可以进来", async () => {
    const r = await dashboardMock.login("AGENT", "owner@shop.ae", "Temp#Pass1");
    expect(r.role).toBe("AGENT");
  });

  it("错码被拒", () => {
    expect(() => dashboardMock.login("AGENT", "+971501234567", "", "123456")).toThrow(/不正确/);
  });

  it("对码放行，并带回主体列表与默认主体", async () => {
    const r = await dashboardMock.login("AGENT", "+971501234567", "", "000000");
    expect(r.role).toBe("AGENT");
    expect(r.operators?.length).toBeGreaterThan(1);
    expect(r.currentOperatorNo).toBe(r.operators?.[0].operatorNo);
  });

  it("员工登录不受影响——它走的是口令，不该被验证码规则误伤", async () => {
    const r = await dashboardMock.login("STAFF", "admin", "x");
    expect(r.role).toBe("ADMIN");
    expect(r.operators).toBeUndefined();   // 运营端没有「我的主体」这个概念
  });
});

describe("发码不泄露号是否注册", () => {
  it("任意手机号都返回 ok——区分开就等于送出一个枚举接口", async () => {
    const known = await dashboardMock.sendLoginOtp("+971501234567");
    const unknown = await dashboardMock.sendLoginOtp("+971509999999");
    expect(known.ok).toBe(true);
    expect(unknown.ok).toBe(true);
  });
});

describe("⑤ 切换主体", () => {
  it("换回一张新 token——只改 operatorNo 的话旧 token 还带着旧数据范围", async () => {
    const first = await dashboardMock.login("AGENT", "+971501234567", "", "000000");
    const r = await dashboardMock.switchOperator("AG002");
    expect(r.currentOperatorNo).toBe("AG002");
    expect(r.token).not.toBe(first.token);
  });

  it("切到不属于自己的主体被拒，且不说「该主体不存在」", () => {
    let msg = "";
    try { dashboardMock.switchOperator("AG999"); } catch (e) { msg = (e as Error).message; }
    expect(msg).toMatch(/不属于/);
    // 「不存在」会泄露别家主体编号的存在性：能拿它逐个试出平台上有哪些主体号
    expect(msg).not.toMatch(/不存在/);
  });

  it("主体列表至少两条——只有一条的话切换器永远不渲染，等于没测", async () => {
    expect((await dashboardMock.listOperators()).length).toBeGreaterThan(1);
  });
});
