// 入驻申请的状态机与守卫测试（ADR-030 §三）。
//
// 这条链上最容易退化的四件事，每一件都不会报错，只会安静地做错：
//  ① 状态机形同虚设 —— 已审完的单子还能再审一次，审核人看不出第二次改了什么；
//  ② 「同手机号至多一张在途」漏掉 —— 一个人刷十张申请，审核队列被淹掉；
//  ③ 多主体申请被当成重复注册拒掉 —— 用户卡死在注册页，而他只是想开第二个主体；
//  ④ 审核通过了但主体没建出来 —— 列表里查无此人，要到联调或上线才发现。
import { describe, expect, it } from "vitest";
import {
  applies, listAgentApplies, acceptAgentApply, auditAgentApply,
  createAgentApply, selfServiceApply, myApply,
} from "./apply";
import { agents } from "./agent";

const OTP = "000000";
/** 每条用例用不同号段，避免「同手机号至多一张在途」互相干扰。 */
let seq = 0;
const freshPhone = () => `+9715099${String(10000 + seq++).slice(-5)}`;

const newApply = (over: Partial<Parameters<typeof createAgentApply>[0]> = {}) =>
  createAgentApply({
    phone: freshPhone(), email: `a${seq}@example.com`,
    operatorName: `测试主体${seq}`, operatorType: "AGENT", ...over,
  });

describe("状态机：非法迁移一律抛错，不静默跳过", () => {
  it("受理只接受「待受理」——已在审核中的再受理一次会被拒", () => {
    const a = newApply();
    acceptAgentApply(a.applyNo, "E1001");
    expect(applies.find((r) => r.applyNo === a.applyNo)!.status).toBe("REVIEWING");
    expect(() => acceptAgentApply(a.applyNo, "E1001")).toThrow(/待受理/);
  });

  it("已通过/已驳回的单子不能再审——否则审核人看不出第二次改了什么", () => {
    const a = newApply();
    auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "材料不全" });
    expect(() => auditAgentApply({ applyNo: a.applyNo, approve: true })).toThrow(/待审核或审核中/);
  });

  it("驳回必须填原因——它会原样回显给申请人，空着等于让人反复猜", () => {
    const a = newApply();
    expect(() => auditAgentApply({ applyNo: a.applyNo, approve: false })).toThrow(/原因/);
    expect(() => auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "  " })).toThrow(/原因/);
  });

  it("未受理也能直接审（SUBMITTED → APPROVED）——受理只是认领，不是必经步骤", () => {
    const a = newApply();
    const r = auditAgentApply({ applyNo: a.applyNo, approve: true });
    expect(r.status).toBe("APPROVED");
  });
});

describe("审核通过 = 激活派生", () => {
  it("★ 真的建出主体——「通过了但档案里没有」这种不一致要到联调才发现", () => {
    const before = agents.length;
    const a = newApply({ operatorName: "派生检查公司" });
    const r = auditAgentApply({ applyNo: a.applyNo, approve: true, shareRate: 0.33, regionScope: "迪拜" });

    expect(agents).toHaveLength(before + 1);
    const created = agents.find((x) => x.agentNo === r.operatorNo)!;
    expect(created.name).toBe("派生检查公司");
    // 审核时可以改写申请人填的比例与辖域 —— 这两项是审核时定的，不是事后补
    expect(created.shareRate).toBe(0.33);
    expect(created.regionScope).toBe("迪拜");
    expect(created.status).toBe("ENABLED");
  });

  it("申请单回写 operatorNo，申请 ↔ 主体双向可查", () => {
    const a = newApply();
    const r = auditAgentApply({ applyNo: a.applyNo, approve: true });
    expect(applies.find((x) => x.applyNo === a.applyNo)!.operatorNo).toBe(r.operatorNo);
  });

  it("驳回后 operatorNo 保持为空——没通过就不该有主体", () => {
    const a = newApply();
    auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "执照过期" });
    const row = applies.find((x) => x.applyNo === a.applyNo)!;
    expect(row.operatorNo).toBeNull();
    expect(row.rejectReason).toBe("执照过期");
  });
});

describe("同手机号至多一张在途", () => {
  it("★ 第二张在途被拒（服务端靠生成列 active_key 强制，这里给的是人话）", () => {
    const phone = freshPhone();
    newApply({ phone });
    expect(() => newApply({ phone })).toThrow(/在途/);
  });

  it("★ 推到终态之后可以再提——驳回重提是正常路径，不能被这条约束卡死", () => {
    const phone = freshPhone();
    const a = newApply({ phone });
    auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "补材料" });
    expect(() => newApply({ phone })).not.toThrow();
  });
});

describe("多主体：手机号已存在不是重复注册", () => {
  it("★ 同一手机号在已有主体后仍可再申请，并带出已知信息给审核台", () => {
    const phone = freshPhone();
    const first = newApply({ phone, email: "boss@first.com" });
    auditAgentApply({ applyNo: first.applyNo, approve: true });

    // 关键断言：不抛错。把这条写反（当成重复注册拒掉）用户会卡死在注册页
    const second = newApply({ phone, email: "boss@first.com", operatorName: "第二个主体" });
    expect(second.phoneAlreadyKnown).toBe(true);
  });

  it("★ 邮箱与已有记录不一致时给出旧掩码——由审核人裁决，不静默覆盖", () => {
    const phone = freshPhone();
    const first = newApply({ phone, email: "old@mail.com" });
    auditAgentApply({ applyNo: first.applyNo, approve: true });

    const second = newApply({ phone, email: "new@mail.com" });
    expect(second.knownEmailMask).toBe("o***@mail.com");
  });
});

describe("自助与代建：同一张表、同一个状态机，只有来源不同", () => {
  it("★ 自助提交落 SELF_SERVICE，代建落 OPS_CREATED，两者进同一个队列", () => {
    const selfPhone = freshPhone();
    const s = selfServiceApply({
      phone: selfPhone, otp: OTP, email: "self@example.com",
      operatorName: "自助提交的主体", operatorType: "AGENT",
    });
    const o = newApply({ operatorName: "代建录入的主体" });

    expect(applies.find((r) => r.applyNo === s.applyNo)!.source).toBe("SELF_SERVICE");
    expect(applies.find((r) => r.applyNo === o.applyNo)!.source).toBe("OPS_CREATED");

    // 缺省队列 = 在途，两条都在里面 —— 「条件相同」在查询侧的体现
    const queue = listAgentApplies({ page: 1, size: 100 }).list.map((r) => r.applyNo);
    expect(queue).toContain(s.applyNo);
    expect(queue).toContain(o.applyNo);
  });

  it("必填校验对两条入口一致——代建放宽的话，补件日后没人记得", () => {
    expect(() => newApply({ operatorName: "  " })).toThrow(/主体名称/);
    expect(() => newApply({ email: "" })).toThrow(/邮箱/);
    expect(() => selfServiceApply({
      phone: freshPhone(), otp: OTP, email: "", operatorName: "x", operatorType: "AGENT",
    })).toThrow(/邮箱/);
  });

  it("自助提交验码，错码被拒", () => {
    expect(() => selfServiceApply({
      phone: freshPhone(), otp: "123456", email: "a@b.com",
      operatorName: "x", operatorType: "AGENT",
    })).toThrow(/验证码/);
  });
});

describe("申请人查进度", () => {
  it("★ 只返回掩码与状态——公开页不能多给任何可枚举的信息", () => {
    const phone = freshPhone();
    const a = newApply({ phone, email: "who@example.com" });
    const view = myApply(phone, OTP);

    expect(view.applyNo).toBe(a.applyNo);
    expect(view.phoneMask).toMatch(/\*{4}/);
    expect(view.emailMask).toBe("w***@example.com");
    // 内部字段一个都不能漏出去
    expect(view as Record<string, unknown>).not.toHaveProperty("principalNo");
    expect(view as Record<string, unknown>).not.toHaveProperty("source");
  });

  it("★ 驳回原因原样回显——这是这一页存在的全部意义", () => {
    const phone = freshPhone();
    const a = newApply({ phone });
    auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "营业执照与主体名称不一致" });
    expect(myApply(phone, OTP).rejectReason).toBe("营业执照与主体名称不一致");
  });

  it("重提后查到的是最新那张", () => {
    const phone = freshPhone();
    const first = newApply({ phone, operatorName: "第一次" });
    auditAgentApply({ applyNo: first.applyNo, approve: false, rejectReason: "补材料" });
    newApply({ phone, operatorName: "重提之后" });
    expect(myApply(phone, OTP).operatorName).toBe("重提之后");
  });

  it("码错了查不到；查不到时不区分「没申请过」与「码错了」", () => {
    const phone = freshPhone();
    newApply({ phone });
    expect(() => myApply(phone, "999999")).toThrow();
  });
});

describe("检索", () => {
  it("缺省只列在途；显式传 status 才看历史", () => {
    const a = newApply();
    auditAgentApply({ applyNo: a.applyNo, approve: false, rejectReason: "x" });
    const inFlight = listAgentApplies({ page: 1, size: 200 }).list.map((r) => r.applyNo);
    expect(inFlight).not.toContain(a.applyNo);
    const rejected = listAgentApplies({ page: 1, size: 200, status: "REJECTED" }).list.map((r) => r.applyNo);
    expect(rejected).toContain(a.applyNo);
  });

  it("★ 关键词只搜主体名与单号，不搜掩码——按掩码搜等于前缀模糊，会捞出不相干的人", () => {
    const a = newApply({ operatorName: "关键词命中公司" });
    expect(listAgentApplies({ page: 1, size: 50, keyword: "关键词命中" }).list.map((r) => r.applyNo))
      .toContain(a.applyNo);
    const row = applies.find((r) => r.applyNo === a.applyNo)!;
    expect(listAgentApplies({ page: 1, size: 50, keyword: row.phoneMask }).total).toBe(0);
  });
});
