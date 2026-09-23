// 适用范围（取价唯一依据）与时段倍率的落库约束。
//
// 这两件事错了都**不会报错**，只会静默按另一个价收钱 —— 与后端
// `PriceScopeResolveTest` 守的是同一类风险，两侧各钉一遍。
import { describe, it, expect, beforeEach } from "vitest";
import * as db from "./index";
import type { PlanScope } from "../../types";

const snapshot = () => db.planScopes.map((x) => ({ ...x }));
let backup: PlanScope[] = [];
beforeEach(() => {
  if (backup.length === 0) backup = snapshot();
  db.planScopes.splice(0, db.planScopes.length, ...backup.map((x) => ({ ...x })));
});

describe("适用范围：同一范围只能有一个方案", () => {
  it("种子里必须有一条 ALL 层 —— 否则没有兜底，取价会拒绝下单", () => {
    expect(db.planScopes.filter((x) => x.scopeType === "ALL")).not.toHaveLength(0);
  });

  it("同一个 (设备类型 × 层 × 引用 × 过滤器) 不允许被两个方案占用", () => {
    const taken = db.planScopes.find((x) => x.scopeType === "SITE")!;
    const other = db.pricePlans.find((p) => p.planNo !== taken.planNo)!;
    expect(() => db.savePlanScope(other.planNo, {
      scopeType: "SITE", scopeRef: taken.scopeRef, deviceType: taken.deviceType,
    })).toThrow(/已经由方案/);
  });

  it("多一个过滤器就是另一个范围，可以并存（同层内它更具体）", () => {
    const taken = db.planScopes.find((x) => x.scopeType === "SITE" && !x.vendorCode)!;
    const other = db.pricePlans.find((p) => p.planNo !== taken.planNo)!;
    const row = db.savePlanScope(other.planNo, {
      scopeType: "SITE", scopeRef: taken.scopeRef, deviceType: taken.deviceType, vendorCode: "sd-power",
    });
    expect(row.vendorCode).toBe("sd-power");
  });

  it("「不限」送的是空串、库里是 null —— 归一后仍算同一个范围（否则唯一性形同虚设）", () => {
    const taken = db.planScopes.find((x) => x.scopeType === "SITE" && !x.vendorCode)!;
    const other = db.pricePlans.find((p) => p.planNo !== taken.planNo)!;
    expect(() => db.savePlanScope(other.planNo, {
      scopeType: "SITE", scopeRef: taken.scopeRef, deviceType: taken.deviceType,
      vendorCode: "", model: "", brandNo: "",   // 表单里选「不限」就是这个形状
    })).toThrow(/已经由方案/);
  });

  it("设备类型必填 —— 缺了它，站点规则会把充电宝的价给按摩椅", () => {
    expect(() => db.savePlanScope("PP001", { scopeType: "SITE", scopeRef: "ST300" }))
      .toThrow(/设备类型/);
  });

  it("ALL 以外的层必须指明引用，空引用不落库", () => {
    expect(() => db.savePlanScope("PP001", { scopeType: "SITE", scopeRef: "", deviceType: "POWERBANK" }))
      .toThrow(/必须指明/);
  });
});

describe("时段倍率：只填一半的配置在入口就拦住", () => {
  it("只填开始时刻 → 拒绝（后端会判不命中，存下来就是一条永不生效的规则）", () => {
    expect(() => db.savePricingSchedule({ name: "半拉子", timeFrom: "18:00", multiplier: 1.5 }))
      .toThrow(/要么都填/);
  });

  it("星期与时刻都空、也没有节假日表达式 → 拒绝", () => {
    expect(() => db.savePricingSchedule({ name: "空的", multiplier: 1.5 })).toThrow(/不能为空/);
  });

  it("只选星期即可（时刻两端都空 = 全天）", () => {
    const r = db.savePricingSchedule({ name: "周末", days: "6,7", multiplier: 1.5 });
    expect(r.days).toBe("6,7");
  });
});
