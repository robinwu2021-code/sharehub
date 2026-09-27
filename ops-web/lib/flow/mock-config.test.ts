// 配置管理的规则 = 对后端的期望。
import { beforeEach, describe, expect, it } from "vitest";
import {
  contentHash, createApp, definitionVersions, getProcess, listDeliveries, listProcesses, processVersions, publishProcess,
  redeliver, resetConfigDb, rotateCredential, upsertSlaPolicy, listSlaPolicies,
} from "./mock-config";
import { isValidationError } from "./errors";

const NOW = new Date("2026-09-27T12:00:00").getTime();
beforeEach(() => resetConfigDb(NOW));

const caught = (f: () => unknown) => { try { f(); } catch (e) { return e; } throw new Error("没有抛错"); };

describe("流程定义", () => {
  it("内容没变再发布：幂等，不生成新版本", () => {
    const before = processVersions("LOC_CONTRACT").length;
    const r = publishProcess(getProcess("LOC_CONTRACT"), "", false, NOW);
    expect(r.created).toBe(false);
    expect(processVersions("LOC_CONTRACT")).toHaveLength(before);
  });

  it("改了内容：新版本号 = 内容哈希，旧版本保留；报告在途单仍按旧版走", () => {
    const def = getProcess("LOC_CONTRACT");
    def.steps[1].timeLimitHours = 12;
    const r = publishProcess(def, "会签时限 24h → 12h", false, NOW);
    expect(r.created).toBe(true);
    expect(r.version).toBe(contentHash({ ...def, steps: def.steps }));
    expect(r.runningOnOlderVersions).toBe(listProcesses().find((p) => p.code === "LOC_CONTRACT")!.running);
    const vs = processVersions("LOC_CONTRACT");
    expect(vs[0]).toMatchObject({ version: r.version, current: true, channel: "CONSOLE", note: "会签时限 24h → 12h" });
    expect(vs.filter((v) => v.current)).toHaveLength(1);
    expect(definitionVersions().find((d) => d.code === "LOC_CONTRACT")!.version).toBe(r.version);
  });

  it("校验失败：一次返回全部错误，每条带字段路径", () => {
    const def = getProcess("LOC_CONTRACT");
    def.steps[0].name = " ";
    def.steps[1].approver.value = "";
    def.steps[1].when = { field: "needsCosign", op: "EQ", value: "" };
    const e = caught(() => publishProcess(def, "", false, NOW));
    expect(isValidationError(e)).toBe(true);
    if (!isValidationError(e)) return;
    expect(e.errors.map((x) => x.path).sort()).toEqual(["steps[0].name", "steps[1].approver.value", "steps[1].when.value"]);
  });

  it("全是条件步不行：至少一个必经步", () => {
    const def = getProcess("FIN_WITHDRAWAL");
    def.steps[0].when = { field: "big", op: "TRUE" };
    const e = caught(() => publishProcess(def, "", false, NOW));
    expect(isValidationError(e) && e.errors[0].path).toBe("steps");
  });

  it("新建：代号唯一且格式合法", () => {
    const e = caught(() => publishProcess({ code: "LOC_CONTRACT", name: "x", crossStepExclusive: false, steps: getProcess("FIN_WITHDRAWAL").steps }, "", true, NOW));
    expect(isValidationError(e) && e.errors.some((x) => x.path === "code")).toBe(true);
    expect(publishProcess({ code: "NEW_FLOW", name: "新流程", crossStepExclusive: false, steps: getProcess("FIN_WITHDRAWAL").steps }, "", true, NOW).created).toBe(true);
    expect(listProcesses().map((p) => p.code)).toContain("NEW_FLOW");
  });
});

describe("SLA 规则", () => {
  it("解决不能短于响应；同类型同优先级只能一条；营业时间要有日历", () => {
    const base = listSlaPolicies()[0];
    const e = caught(() => upsertSlaPolicy({ ...base, respondMin: 300, resolveMin: 60 }));
    expect(isValidationError(e) && e.errors[0].path).toBe("resolveMin");
    const dup = caught(() => upsertSlaPolicy({ ...base, code: "x", priority: "HIGH" }));
    expect(isValidationError(dup) && dup.errors.some((x) => x.path === "priority")).toBe(true);
    const cal = caught(() => upsertSlaPolicy({ ...base, businessHours: true, calendar: "nope" }));
    expect(isValidationError(cal) && cal.errors[0].path).toBe("calendar");
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
