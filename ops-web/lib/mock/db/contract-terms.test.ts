import { describe, it, expect } from "vitest";
import { saveContract, contracts, venues, sites } from "./location";

/**
 * 进场合同条款 mock 回归 —— 钉的是与后端 ContractServiceImpl.fill 的一致性（OM02-06）：
 * 分成模式推断、各模式的必填、比例 / 保底 / 进场费的归一化、币种与周期缺省；
 * 以及「入参平铺、读出参嵌在 terms」这层转换。
 *
 * 为什么要钉：表单此前只给比例与进场费，分成模式、保底等录不进去；
 * 补上之后最容易错的是「换了模式，另一种模式的旧值还留着」—— 那会让保底补差算错。
 */
const base = () => {
  const site = sites.find((s) => s.venueNo)!;
  return { venueNo: site.venueNo!, siteNo: site.siteNo, startAt: "2026-11-01", endAt: "2027-10-31" };
};

describe("进场合同条款（mock 与后端 ContractServiceImpl 同口径）", () => {
  it("平铺入参收进 terms，缺省币种 AED、按月、按净额", () => {
    const c = saveContract({ ...base(), shareMode: "SHARE", shareRate: 0.2, depositAmount: 5000, depositTerms: "撤场退还", exclusive: true, deviceQuota: 3 });
    expect(c.status).toBe("DRAFT");
    expect(c.terms).toMatchObject({
      shareMode: "SHARE", shareBase: "NET", currency: "AED", settlePeriod: "MONTH",
      depositAmount: 5000, depositTerms: "撤场退还", exclusive: true, deviceQuota: 3, guaranteeAmount: null,
    });
    expect(c.shareRate).toBe(0.2);
    expect(c).not.toHaveProperty("depositAmount");
  });

  it("纯分成与保底 + 分成要比例 > 0；保底 + 分成还要保底 > 0", () => {
    expect(() => saveContract({ ...base(), shareMode: "SHARE", shareRate: 0 })).toThrow(/比例|share rate/i);
    expect(() => saveContract({ ...base(), shareMode: "GUARANTEE", shareRate: 0.2 })).toThrow(/保底|guarantee/i);
    const g = saveContract({ ...base(), shareMode: "GUARANTEE", shareRate: 0.2, guaranteeAmount: 3000 });
    expect(g.terms?.guaranteeAmount).toBe(3000);
  });

  it("固定进场费要进场费 > 0，比例归零；免费入驻比例与费用都清零", () => {
    expect(() => saveContract({ ...base(), shareMode: "ENTRY_FEE", entryFee: 0 })).toThrow(/进场费|entry fee/i);
    const e = saveContract({ ...base(), shareMode: "ENTRY_FEE", entryFee: 1500, shareRate: 0.3 });
    expect(e.shareRate).toBe(0);
    expect(e.entryFee).toBe(1500);
    const f = saveContract({ ...base(), shareMode: "FREE", entryFee: 800, shareRate: 0.3, guaranteeAmount: 999 });
    expect([f.shareRate, f.entryFee, f.terms?.guaranteeAmount]).toEqual([0, 0, null]);
  });

  it("换模式不留旧模式的值：保底 + 分成改成纯分成后保底清空", () => {
    const g = saveContract({ ...base(), shareMode: "GUARANTEE", shareRate: 0.2, guaranteeAmount: 3000 });
    const s = saveContract({ ...base(), contractNo: g.contractNo, shareMode: "SHARE", shareRate: 0.2, guaranteeAmount: 3000 });
    expect(s.terms?.shareMode).toBe("SHARE");
    expect(s.terms?.guaranteeAmount).toBeNull();
  });

  it("未给模式按填了什么推断；比例越界报错", () => {
    expect(saveContract({ ...base(), shareRate: 0.1 }).terms?.shareMode).toBe("SHARE");
    expect(saveContract({ ...base(), shareRate: 0, entryFee: 200 }).terms?.shareMode).toBe("ENTRY_FEE");
    expect(saveContract({ ...base(), shareRate: 0, entryFee: 0 }).terms?.shareMode).toBe("FREE");
    expect(() => saveContract({ ...base(), shareRate: 20 })).toThrow(/0~1|between/);
  });

  it("只改状态的夹具不覆盖既有条款", () => {
    const g = saveContract({ ...base(), shareMode: "GUARANTEE", shareRate: 0.2, guaranteeAmount: 3000, currency: "SAR" });
    const again = saveContract({ contractNo: g.contractNo, status: "ACTIVE" });
    expect(again.terms).toMatchObject({ shareMode: "GUARANTEE", guaranteeAmount: 3000, currency: "SAR" });
  });

  it("种子合同都带条款（详情的条款区与编辑表单才有东西可摊）", () => {
    expect(venues.length).toBeGreaterThan(0);
    for (const c of contracts) expect(c.terms, `${c.contractNo} 没有条款`).toBeTruthy();
  });
});
