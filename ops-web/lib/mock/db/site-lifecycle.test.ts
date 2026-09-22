// 门店生命周期阶段流转的守卫测试。
//
// 生命周期页从前是纯只读，`POST /api/ops/site-lifecycles/{siteNo}/stage` 后端早就有、前端从没调过。
// 补上写操作后，三件事必须由本 db 层兜住，不能指望页面自觉：
//  ① 阶段取值必须在 SSOT 取值域内；② 空转（目标=当前）拒绝——后端就是这么判的；
//  ③ 每次成功流转必须 append 一行 log（不留痕即不算流转）。
// 另有一条反向断言：**不能凭空加严**——生命周期没有单向状态机，CHURNED 重签回来必须放行。
import { describe, expect, it } from "vitest";
import { siteLifecycles, siteLifecycleLogs, changeSiteStage, SiteLifecycleError } from "./location";
import { SITE_STAGES, nextSiteStages, canSiteStageTransition, type SiteStage } from "../../types";

const rowOf = (siteNo: string) => siteLifecycles.find((x) => x.siteNo === siteNo)!;

describe("阶段流转守卫", () => {
  it("非法阶段取值直接拒绝（取值域以 SITE_STAGES 为准）", () => {
    expect(() => changeSiteStage("ST300", { stage: "DEAD" as SiteStage })).toThrow(SiteLifecycleError);
    expect(() => changeSiteStage("ST300", { stage: "DEAD" as SiteStage })).toThrow(/非法/);
  });

  it("空转拒绝：目标阶段等于当前阶段时不产生流转，也不留痕", () => {
    const cur = rowOf("ST300").stage;
    const logs = siteLifecycleLogs.length;
    expect(() => changeSiteStage("ST300", { stage: cur })).toThrow(SiteLifecycleError);
    expect(siteLifecycleLogs.length).toBe(logs);
  });

  it("目标阶段必填", () => {
    expect(() => changeSiteStage("ST300", { stage: "" as SiteStage })).toThrow(SiteLifecycleError);
    expect(() => changeSiteStage("", { stage: "SIGNED" })).toThrow(SiteLifecycleError);
  });
});

describe("阶段流转生效与留痕", () => {
  it("合法流转：阶段与 stageAt 更新，操作人写入，log append 一行含 from/to", () => {
    const before = siteLifecycleLogs.length;
    const from = rowOf("ST301").stage;
    const r = changeSiteStage("ST301", { stage: "ACTIVE", reason: "首月达标转运营", operator: "Sara Ops" });

    expect(r.stage).toBe("ACTIVE");
    expect(r.stageAt).toBe(new Date().toISOString().slice(0, 10));
    expect(r.owner).toBe("Sara Ops");
    expect(siteLifecycleLogs.length).toBe(before + 1);
    expect(siteLifecycleLogs[0]).toMatchObject({
      siteNo: "ST301", fromStage: from, toStage: "ACTIVE", operator: "Sara Ops", reason: "首月达标转运营",
    });
  });

  it("gmvLtm 是阶段决策快照：不传则沿用上一次的值，不被清零", () => {
    const gmv = rowOf("ST300").gmvLtm;
    expect(gmv).toBeGreaterThan(0);
    expect(changeSiteStage("ST300", { stage: "CHURNED" }).gmvLtm).toBe(gmv);
  });

  it("站点没有生命周期行时建档而非报错，fromStage 记 null（与后端 insert 分支一致）", () => {
    const fresh = "ST310";
    expect(siteLifecycles.some((x) => x.siteNo === fresh)).toBe(false);
    const r = changeSiteStage(fresh, { stage: "PROSPECTING" });
    expect(r.siteNo).toBe(fresh);
    expect(siteLifecycles.some((x) => x.siteNo === fresh)).toBe(true);
    expect(siteLifecycleLogs[0]).toMatchObject({ siteNo: fresh, fromStage: null, toStage: "PROSPECTING" });
  });
});

describe("SSOT：页面按钮可用性与本层校验同源", () => {
  it("不加严：任意阶段都能去到除自己以外的全部阶段（CHURNED 重签回来是正常业务）", () => {
    for (const from of SITE_STAGES) {
      expect(nextSiteStages(from)).toEqual(SITE_STAGES.filter((s) => s !== from));
      expect(canSiteStageTransition(from, from)).toBe(false);
    }
    // 反向流转必须真的能跑通，否则「按钮给点、接口报错」或反之
    expect(changeSiteStage("ST303", { stage: "SIGNED", reason: "重新签回" }).stage).toBe("SIGNED");
  });

  it("没有终态：每个阶段都至少有一个合法目标，所以「推进阶段」按钮不会永久灰掉", () => {
    expect(SITE_STAGES.every((s) => nextSiteStages(s).length > 0)).toBe(true);
  });
});
