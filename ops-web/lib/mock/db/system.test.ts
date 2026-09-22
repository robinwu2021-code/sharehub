// 系统域 S6/S7 的四条不变量（都是"错了会出事"而不是"错了不好看"）：
//  ① 地区树自洽：无孤儿父、level 等于树深度、树上节点数 = 扁平行数（不丢不重）；
//  ② 供应商探测确定性：同一供应商每次同结论，否则运维分不清是配置问题还是探测抖；
//  ③ 模板预览/试发：缺变量必须拦住（带 {{}} 发出去是事故），试发必须带幂等键；
//  ④ 重发（拍板 #6）：必须带幂等键、同键第二次拒绝、只能重发失败记录、原记录一字不改。
import { describe, it, expect } from "vitest";
import {
  regions, listRegionTree, saveRegion,
  testVendorConnectivity, VendorProbeError,
  notifyTemplates, notifyLogs, notifyBlacklist,
  previewNotifyTemplate, testSendNotifyTemplate, resendNotifyLog, NotifySendError,
  openApiApps, resetOpenApiAppSecret,
} from "./system";
import { vendors } from "./device";
import type { RegionNode } from "../../types";

const flat = (ns: RegionNode[]): RegionNode[] => ns.flatMap((n) => [n, ...flat(n.children)]);

describe("S6 地区库树形", () => {
  it("扁平数据无孤儿父：每个 parentId 都指向存在的行", () => {
    const ids = new Set(regions.map((r) => r.regionId));
    const orphans = regions.filter((r) => r.parentId && !ids.has(r.parentId));
    expect(orphans.map((r) => `${r.regionId}→${r.parentId}`)).toEqual([]);
  });

  it("level 与 parent 名称同 parentId 一致（三者必须说同一件事）", () => {
    const byId = new Map(regions.map((r) => [r.regionId, r]));
    for (const r of regions) {
      const parent = r.parentId ? byId.get(r.parentId) : undefined;
      expect(r.level, r.regionId).toBe(parent ? parent.level + 1 : 1);
      expect(r.parent, r.regionId).toBe(parent ? parent.name : "-");
    }
  });

  it("树不丢不重：节点数 = 扁平行数，且每个节点的 level 就是它的深度", () => {
    const roots = listRegionTree();
    expect(flat(roots)).toHaveLength(regions.length);
    const walk = (ns: RegionNode[], depth: number) => {
      for (const n of ns) { expect(n.level, n.regionId).toBe(depth); walk(n.children, depth + 1); }
    };
    walk(roots, 1);
  });

  it("新增子区域后出现在对应父节点下（不是又冒一个根）", () => {
    const rootsBefore = listRegionTree().length;
    saveRegion({ regionId: "DU-JLT", name: "JLT", parentId: "AE-DU", parent: "迪拜", level: 3, cityCount: 0 });
    const roots = listRegionTree();
    expect(roots).toHaveLength(rootsBefore);
    const dubai = flat(roots).find((r) => r.regionId === "AE-DU");
    expect(dubai?.children.map((c) => c.regionId)).toContain("DU-JLT");
  });
});

describe("S7 供应商连通性测试", () => {
  it("同一供应商连测两次结论一致（探测本身不许抖）", () => {
    const code = vendors[0].vendorCode;
    const a = testVendorConnectivity(code);
    const b = testVendorConnectivity(code);
    expect({ ok: a.ok, latencyMs: a.latencyMs }).toEqual({ ok: b.ok, latencyMs: b.latencyMs });
    expect(a.ok).toBe(true);
  });

  it("停用的供应商不发起探测，且说清「为什么没测」而不是含混地判不通", () => {
    const v = vendors[0];
    const prev = v.status;
    v.status = "DISABLED";
    const r = testVendorConnectivity(v.vendorCode);
    v.status = prev;
    expect(r.ok).toBe(false);
    expect(r.latencyMs).toBe(0);
    expect(r.detail).toContain("DISABLED");
  });

  it("云对接型缺 API 基址 → 判不通（否则等到设备离线才发现配漏了）", () => {
    const v = vendors.find((x) => x.accessMode === "HTTP_API")!;
    const prev = v.apiBase;
    v.apiBase = null;
    const r = testVendorConnectivity(v.vendorCode);
    v.apiBase = prev;
    expect(r.ok).toBe(false);
    expect(r.message).toContain("API 基址");
  });

  it("不存在的供应商直接抛（前端不该测一个查不到的厂商）", () => {
    expect(() => testVendorConnectivity("no-such-vendor")).toThrow(VendorProbeError);
  });
});

describe("S7 通知模板 预览 / 试发", () => {
  const promo = notifyTemplates.find((t) => t.scene === "PROMO")!;
  const otp = notifyTemplates.find((t) => t.scene === "OTP" && t.status === "ENABLED")!;

  it("预览替换变量，且不产生任何发送记录（预览不计费）", () => {
    const before = notifyLogs.length;
    const p = previewNotifyTemplate(otp.templateNo, { code: "1234" });
    expect(p.rendered).toContain("1234");
    expect(p.rendered).not.toContain("{{code}}");
    expect(notifyLogs).toHaveLength(before);
  });

  it("没有示例值又没填的变量进 missingVars，且正文里原样留占位", () => {
    const p = previewNotifyTemplate(promo.templateNo);
    expect(p.missingVars).toContain("couponName");
    expect(p.rendered).toContain("{{couponName}}");
  });

  it("试发：必须带幂等键，同键第二次拒绝；成功则新增一条发送记录", () => {
    expect(() => testSendNotifyTemplate(otp.templateNo, { target: "+9715011122233", idempotencyKey: "  " }))
      .toThrow(/必须携带幂等键/);

    const before = notifyLogs.length;
    const log = testSendNotifyTemplate(otp.templateNo, { target: "+9715011122233", vars: { code: "9911" }, idempotencyKey: "TPL-TEST-1" });
    expect(notifyLogs).toHaveLength(before + 1);
    expect(log.idempotencyKey).toBe("TPL-TEST-1");
    expect(log.resendOf).toBeNull();
    // 目标必须脱敏落库：运营端不承载完整联系方式
    expect(log.target).not.toBe("+9715011122233");

    expect(() => testSendNotifyTemplate(otp.templateNo, { target: "+9715011122233", vars: { code: "9911" }, idempotencyKey: "TPL-TEST-1" }))
      .toThrow(/拒绝重复发送/);
    expect(notifyLogs).toHaveLength(before + 1);
  });

  it("变量没填全不许试发（拦在服务端，不只靠前端禁用按钮）", () => {
    const before = notifyLogs.length;
    expect(() => testSendNotifyTemplate(promo.templateNo, { target: "+9715011122233", idempotencyKey: "TPL-TEST-MISSING" }))
      .toThrow(/变量未填全/);
    expect(notifyLogs).toHaveLength(before);
    // 校验失败不烧键：改完变量还能用同一把键
    expect(testSendNotifyTemplate(promo.templateNo, {
      target: "+9715011122233",
      vars: { title: "斋月特惠", body: "满减", couponName: "RAMADAN5" },
      idempotencyKey: "TPL-TEST-MISSING",
    }).status).toBe("SENT");
  });

  it("停用模板不许试发", () => {
    const off = notifyTemplates.find((t) => t.status === "DISABLED")!;
    expect(() => testSendNotifyTemplate(off.templateNo, { target: "+9715011122233", idempotencyKey: "TPL-TEST-OFF" }))
      .toThrow(/已停用/);
  });
});

describe("S7 发送记录重发（拍板 #6：幂等键 + mock 层拒绝重复）", () => {
  /** 未被拉黑的失败记录：拉黑命中的另有专门用例。 */
  const resendable = () => notifyLogs.find((l) =>
    l.status === "FAILED" && !l.resendOf &&
    !notifyBlacklist.some((b) => b.target === l.target && (b.channel === "ALL" || b.channel === l.channel)))!;

  it("必须带幂等键", () => {
    expect(() => resendNotifyLog(resendable().logNo, { idempotencyKey: "" })).toThrow(/必须携带幂等键/);
  });

  it("重发是新增一条，原记录一字不改（审计要看得见发了两次）", () => {
    const src = resendable();
    const snapshot = { ...src };
    const before = notifyLogs.length;

    const fresh = resendNotifyLog(src.logNo, { idempotencyKey: "RS-TEST-1" });
    expect(notifyLogs).toHaveLength(before + 1);
    expect(fresh.logNo).not.toBe(src.logNo);
    expect(fresh.resendOf).toBe(src.logNo);
    expect(fresh.status).toBe("SENT");
    expect(fresh.idempotencyKey).toBe("RS-TEST-1");
    // 渠道/目标/模板沿用原记录，不给运营在重发时偷偷换目标的机会
    expect({ ch: fresh.channel, target: fresh.target, tpl: fresh.templateNo })
      .toEqual({ ch: snapshot.channel, target: snapshot.target, tpl: snapshot.templateNo });
    expect(notifyLogs.find((l) => l.logNo === snapshot.logNo)).toEqual(snapshot);
  });

  it("同一把键第二次拒绝，且不再落新单（重复扣费是真资损）", () => {
    const src = resendable();
    const before = notifyLogs.length;
    resendNotifyLog(src.logNo, { idempotencyKey: "RS-TEST-DUP" });
    expect(() => resendNotifyLog(src.logNo, { idempotencyKey: "RS-TEST-DUP" })).toThrow(/拒绝重复发送/);
    expect(notifyLogs).toHaveLength(before + 1);
    expect(notifyLogs.filter((l) => l.idempotencyKey === "RS-TEST-DUP")).toHaveLength(1);
  });

  it("已发送记录不许重发（要补发请走模板试发）", () => {
    const sent = notifyLogs.find((l) => l.status === "SENT")!;
    expect(() => resendNotifyLog(sent.logNo, { idempotencyKey: "RS-TEST-SENT" })).toThrow(/不允许重发/);
  });

  it("目标已在触达拉黑（未到期）→ 拒绝重发", () => {
    const blocked = notifyLogs.find((l) =>
      l.status === "FAILED" &&
      notifyBlacklist.some((b) => b.target === l.target && (b.channel === "ALL" || b.channel === l.channel) &&
        (!b.expireAt || new Date(b.expireAt).getTime() > Date.now())))!;
    expect(blocked).toBeTruthy();
    expect(() => resendNotifyLog(blocked.logNo, { idempotencyKey: "RS-TEST-BLOCKED" })).toThrow(/触达拉黑/);
  });

  it("不存在的流水号直接抛", () => {
    expect(() => resendNotifyLog("NL_NOT_EXIST", { idempotencyKey: "RS-TEST-404" })).toThrow(/不存在/);
  });
});

describe("S7 OpenAPI 密钥重置", () => {
  it("换掩码 + 落重置时间，AppKey 不变（AppKey 是公开标识，不是密钥）", () => {
    const app = openApiApps[0];
    const before = { appKey: app.appKey, masked: app.appSecretMasked };
    const after = resetOpenApiAppSecret(app.appNo);
    expect(after.appKey).toBe(before.appKey);
    expect(after.appSecretMasked).not.toBe(before.masked);
    expect(after.secretResetAt).toBeTruthy();
  });

  it("返回值只有掩码，永不回传明文 secret", () => {
    const after = resetOpenApiAppSecret(openApiApps[1].appNo);
    expect(after.appSecretMasked).toContain("****");
    expect(Object.keys(after)).not.toContain("appSecret");
  });

  it("不存在的应用号直接抛", () => {
    expect(() => resetOpenApiAppSecret("APP_NOT_EXIST")).toThrow(/不存在/);
  });
});
