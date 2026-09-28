// 配置管理的规则 = 对后端的期望。
import { beforeEach, describe, expect, it } from "vitest";
import { setMockOperatorPerms } from "./mock-auth";
import {
  contentHash, createApp, definitionVersions, getTemplate, listDeliveries, listTemplates, templateVersions, publishTemplate,
  redeliver, resetConfigDb, rotateCredential, upsertSlaPolicy, listSlaPolicies,
} from "./mock-config";
import { isValidationError } from "./errors";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => { setMockOperatorPerms(["*"]); resetConfigDb(NOW); });

const caught = (f: () => unknown) => { try { f(); } catch (e) { return e; } throw new Error("没有抛错"); };

describe("审批模板", () => {
  it("内容没变再发布：幂等，不生成新版本", () => {
    const before = templateVersions("LOC_CONTRACT").length;
    const r = publishTemplate(getTemplate("LOC_CONTRACT"), "", false, NOW);
    expect(r.created).toBe(false);
    expect(templateVersions("LOC_CONTRACT")).toHaveLength(before);
  });

  it("改了内容：新版本号 = 内容哈希，旧版本保留；报告在途单仍按旧版走", () => {
    const def = getTemplate("LOC_CONTRACT");
    def.steps[1].dueHours = 12;
    const r = publishTemplate(def, "会签时限 24h → 12h", false, NOW);
    expect(r.created).toBe(true);
    expect(r.version).toBe(contentHash({ ...def, steps: def.steps }));
    expect(r.runningOnOlderVersions).toBe(listTemplates().find((p) => p.code === "LOC_CONTRACT")!.running);
    const vs = templateVersions("LOC_CONTRACT");
    expect(vs[0]).toMatchObject({ version: r.version, current: true, channel: "CONSOLE", note: "会签时限 24h → 12h" });
    expect(vs.filter((v) => v.current)).toHaveLength(1);
    expect(definitionVersions().find((d) => d.code === "LOC_CONTRACT")!.version).toBe(r.version);
  });

  it("校验失败：一次返回全部错误，每条带字段路径", () => {
    const def = getTemplate("LOC_CONTRACT");
    def.steps[0].name = { zh: " " };
    def.steps[1].approver.value = "";
    def.steps[1].when = { field: "needsCosign", op: "EQ", value: "" };
    const e = caught(() => publishTemplate(def, "", false, NOW));
    expect(isValidationError(e)).toBe(true);
    if (!isValidationError(e)) return;
    expect(e.errors.map((x) => x.path).sort()).toEqual(["steps[0].name", "steps[1].approver.value", "steps[1].when.value"]);
  });

  it("全是条件步不行：至少一个必经步", () => {
    const def = getTemplate("FIN_WITHDRAWAL");
    def.steps[0].when = { field: "big", op: "TRUE" };
    const e = caught(() => publishTemplate(def, "", false, NOW));
    expect(isValidationError(e) && e.errors[0].path).toBe("steps");
  });

  it("新建：代号唯一且格式合法", () => {
    const rules = { selfApproval: false, crossStepExclusive: true };
    const e = caught(() => publishTemplate({ code: "LOC_CONTRACT", name: { zh: "x" }, rules, steps: getTemplate("FIN_WITHDRAWAL").steps }, "", true, NOW));
    expect(isValidationError(e) && e.errors.some((x) => x.path === "code")).toBe(true);
    expect(publishTemplate({ code: "NEW_FLOW", name: { en: "New flow" }, rules, steps: getTemplate("FIN_WITHDRAWAL").steps }, "", true, NOW).created).toBe(true);
    expect(listTemplates().find((p) => p.code === "NEW_FLOW")!.name).toBe("New flow");
  });

  it("会签只允许成员 / 组 / 字段指定：角色、权限码不可枚举", () => {
    const def = getTemplate("CS_REFUND");
    def.steps[0].mode = "ALL";
    const e = caught(() => publishTemplate(def, "", false, NOW));
    expect(isValidationError(e) && e.errors.map((x) => x.path)).toEqual(["steps[0].mode"]);
    def.steps[0].approver = { kind: "GROUP", value: "cs-leads" };
    expect(publishTemplate(def, "", false, NOW).created).toBe(true);
  });

  it("成员审批人是 ID 列表：去空项、去重；空列表报必填", () => {
    const def = getTemplate("FIN_WITHDRAWAL");
    def.steps[0].approver = { kind: "MEMBER", value: [" omar", "", "sara", "omar"] };
    def.steps[0].mode = "ALL";
    publishTemplate(def, "", false, NOW);
    expect(getTemplate("FIN_WITHDRAWAL").steps[0].approver).toEqual({ kind: "MEMBER", value: ["omar", "sara"] });
    def.steps[0].approver = { kind: "MEMBER", value: [" ", ""] };
    const e = caught(() => publishTemplate(def, "", false, NOW));
    expect(isValidationError(e) && e.errors.map((x) => x.path)).toEqual(["steps[0].approver.value"]);
  });

  it("字段指定也要给字段代号；时限可不填", () => {
    const def = getTemplate("FIN_WITHDRAWAL");
    def.steps[0].approver = { kind: "FIELD", value: "" };
    def.steps[0].dueHours = undefined;
    const e = caught(() => publishTemplate(def, "", false, NOW));
    expect(isValidationError(e) && e.errors.map((x) => x.path)).toEqual(["steps[0].approver.value"]);
  });
});

describe("SLA 策略（后端 O2-8 的通用形状）", () => {
  it("内容不变再发布是幂等的；改了才生成新版本", () => {
    const base = listSlaPolicies().find((p) => p.code === "ops")!;
    expect(upsertSlaPolicy(base)).toEqual({ version: base.version, created: false });
    base.targets[0].targetsMin.RESOLUTION = 180;
    const r = upsertSlaPolicy(base);
    expect(r.created).toBe(true);
    expect(r.version).not.toBe(base.version);
    expect(listSlaPolicies().find((p) => p.code === "ops")!.version).toBe(r.version);
  });

  it("兜底行只能在最后；时限只能给本策略的指标且 > 0；日历要存在 —— 一次给全、路径按字段", () => {
    const base = listSlaPolicies().find((p) => p.code === "ops")!;
    base.targets = [{ targetsMin: { RESOLUTION: 60 } }, { when: { field: "priority", op: "EQ", value: "HIGH" }, targetsMin: { NOPE: 5, RESOLUTION: 0 }, calendar: "nope" }];
    const e = caught(() => upsertSlaPolicy(base));
    expect(isValidationError(e) && e.errors.map((x) => x.path).sort()).toEqual(
      ["targets[1]", "targets[1].calendar", "targets[1].targetsMin.NOPE", "targets[1].targetsMin.RESOLUTION"]);
  });

  it("新建：代号格式与唯一；至少一个指标、一行目标、名称至少一种语言", () => {
    const e = caught(() => upsertSlaPolicy({ code: "ops", name: {}, metrics: [], targets: [] }, true));
    expect(isValidationError(e) && e.errors.map((x) => x.path).sort()).toEqual(["code", "metrics", "name", "targets"]);
    expect(upsertSlaPolicy({ code: "vip", name: { en: "VIP" }, metrics: [{ code: "RESOLUTION", name: { en: "Resolution" } }],
      targets: [{ targetsMin: { RESOLUTION: 60 } }] }, true).created).toBe(true);
  });
});

describe("接入", () => {
  it("凭证只在创建 / 轮换时给，每次都不同", () => {
    const { app, secret } = createApp({ code: "ERP", name: "ai-erp", callbackUrl: "https://erp.neargo.ai/cb", timeZone: "Asia/Dubai" });
    const r = rotateCredential(app.id);
    expect(secret).toMatch(/^osk_live_/);
    expect(r.secret).not.toBe(secret);
  });

  it("回调地址必须 https", () => {
    const e = caught(() => createApp({ code: "BAD", name: "x", callbackUrl: "http://a", timeZone: "UTC" }));
    expect(isValidationError(e) && e.errors[0].path).toBe("callbackUrl");
  });

  it("只有失败的投递能重投；重投后成功、次数 +1", () => {
    const failed = listDeliveries().find((d) => d.status === "FAILED")!;
    const ok = listDeliveries().find((d) => d.status === "DELIVERED")!;
    expect(() => redeliver(ok.id, NOW)).toThrow(/失败/);
    const r = redeliver(failed.id, NOW);
    expect(r.status).toBe("DELIVERED");
    expect(r.attempts).toBe(4);
  });
});
