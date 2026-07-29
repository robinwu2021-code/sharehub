// G1 软删除单测（TDD §10.1）。
//
// 归档的三条语义靠人眼在 15 个页面上点是点不完的，这里用表驱动一次性钉死：
//   ① 归档后**默认列表查不到**   ② `showArchived` 打开**能查到**   ③ 恢复后**回到默认列表**
// 只要漏了 liveHit（软删除最常见的漏实现），第 ① 条立刻红。
//
// 走 mockApi 而不是直接调 db：list 的归档过滤有的落在 db 层、有的落在 mock 切片的内联
// paginate 里，从契约这一层断言才不管实现落在哪儿。
import { describe, it, expect } from "vitest";
import { mockApi as api } from "./mock";
import type { ArchiveQ } from "./query";

/** 一页拉全（mock 数据量小），避免「不在第 1 页」被误判成「查不到」。 */
const ALL: ArchiveQ = { size: 999 };

interface Case {
  /** 用例名（出现在测试报告里） */
  label: string;
  /** 被归档的业务键 —— 必须是 mock 里真实存在且未预归档的行 */
  key: string;
  keys: (q: ArchiveQ) => Promise<string[]>;
  archive: (k: string) => Promise<unknown>;
  unarchive: (k: string) => Promise<unknown>;
}

/** PageResult 版：抽出业务键数组。 */
const page = <T>(
  list: (q: ArchiveQ) => Promise<{ list: T[] }>,
  keyOf: (r: T) => string,
) => async (q: ArchiveQ) => (await list(q)).list.map(keyOf);

const CASES: Case[] = [
  { label: "设备台账（机柜）", key: "CAB1000",
    keys: page((q) => api.listCabinets(q), (c) => c.cabinetNo),
    archive: api.archiveCabinet, unarchive: api.unarchiveCabinet },
  { label: "充电宝", key: "PB20000",
    keys: page((q) => api.listPowerbanks(q), (p) => p.powerbankNo),
    archive: api.archivePowerbank, unarchive: api.unarchivePowerbank },
  { label: "站点", key: "ST300",
    keys: page((q) => api.listSites(q), (s) => s.siteNo),
    archive: api.archiveSite, unarchive: api.unarchiveSite },
  { label: "点位", key: "LOC200",
    keys: page((q) => api.listLocations(q), (l) => l.locationNo),
    archive: api.archivePoint, unarchive: api.unarchivePoint },
  { label: "场地方", key: "VEN300",
    keys: page((q) => api.listVenues(q), (v) => v.venueNo),
    archive: api.archiveVenue, unarchive: api.unarchiveVenue },
  { label: "代理商档案", key: "AG001",
    keys: page((q) => api.listAgents(q), (a) => a.agentNo),
    archive: api.archiveAgent, unarchive: api.unarchiveAgent },
  { label: "计费模板", key: "PP001",
    keys: page((q) => api.listPricePlans(q), (p) => p.planNo),
    archive: api.archivePricePlan, unarchive: api.unarchivePricePlan },
  { label: "优惠券", key: "CP800",
    keys: page((q) => api.listCoupons(q), (c) => c.couponNo),
    archive: api.archiveCoupon, unarchive: api.unarchiveCoupon },
  { label: "公告", key: "NTC900",
    keys: page((q) => api.listNotices(q), (n) => n.noticeNo),
    archive: api.archiveNotice, unarchive: api.unarchiveNotice },
  { label: "告警代码", key: "OFFLINE",
    keys: page((q) => api.listAlarmCodes(q), (c) => c.code),
    archive: api.archiveAlarmCode, unarchive: api.unarchiveAlarmCode },
  { label: "通知规则", key: "AR600",
    keys: page((q) => api.listAlarmRules(q), (r) => r.ruleNo),
    archive: api.archiveAlarmRule, unarchive: api.unarchiveAlarmRule },
  { label: "银行", key: "ENBD",
    keys: page((q) => api.listBanks(q), (b) => b.bankCode),
    archive: api.archiveBank, unarchive: api.unarchiveBank },
  { label: "问题类型", key: "ISS901",
    keys: page((q) => api.listProblems(q), (p) => p.problemNo),
    archive: api.archiveProblem, unarchive: api.unarchiveProblem },
  { label: "充值套餐", key: "RP900",
    keys: page((q) => api.listRechargePackages(q), (p) => p.packageNo),
    archive: api.archiveRechargePackage, unarchive: api.unarchiveRechargePackage },
  // 角色：listRoles 不分页（角色数量少），单独适配
  { label: "角色", key: "__ARCHIVE_TEST__",
    keys: async (q) => (await api.listRoles(q)).map((r) => r.roleNo),
    archive: api.archiveRole, unarchive: api.unarchiveRole },
];

describe("G1 软删除：归档 / 恢复", () => {
  // 角色用例需要一条非内置角色（内置角色禁止归档），先造出来
  it("准备：新增一条非内置角色供归档用例使用", async () => {
    await api.saveRoleRow({
      roleNo: "__ARCHIVE_TEST__", code: "ARCHIVE_TEST", name: "归档测试角色",
      permCount: 0, memberCount: 0, builtin: false, dataScope: "SELF", scopeValues: "",
      archivedAt: null,
    });
    expect(await CASES[CASES.length - 1].keys(ALL)).toContain("__ARCHIVE_TEST__");
  });

  it.each(CASES.map((c) => [c.label, c] as const))(
    "%s：归档后默认查不到 / showArchived 能查到 / 恢复后回到默认列表",
    async (_label, c) => {
      // 前置：初始状态就应该在默认列表里，否则用例选的 key 本身有问题
      expect(await c.keys(ALL), "用例选的业务键必须是未归档的真实数据").toContain(c.key);

      await c.archive(c.key);
      expect(await c.keys(ALL), "归档后不该出现在默认列表").not.toContain(c.key);
      expect(await c.keys({ ...ALL, showArchived: true }), "显示已归档时应能查到").toContain(c.key);

      await c.unarchive(c.key);
      expect(await c.keys(ALL), "恢复后应回到默认列表").toContain(c.key);
    },
  );

  it("归档写入 archivedAt 时间戳（不是布尔位——归档时间本身是审计信息）", async () => {
    const archived = await api.archiveBank("FAB");
    expect(archived.archivedAt).toBeTypeOf("string");
    expect(Number.isNaN(Date.parse(archived.archivedAt!))).toBe(false);
    const restored = await api.unarchiveBank("FAB");
    expect(restored.archivedAt).toBeNull();
  });

  it("mock 里预置的已归档行默认不出现，打开开关才出现", async () => {
    // 计费模板 PP004「旧活动价」在 mock 里就是已归档态，用来演示「显示已归档」开关
    const live = (await api.listPricePlans(ALL)).list.map((p) => p.planNo);
    const all = (await api.listPricePlans({ ...ALL, showArchived: true })).list.map((p) => p.planNo);
    expect(live).not.toContain("PP004");
    expect(all).toContain("PP004");
  });

  it("内置角色不可归档（登录鉴权依赖其存在，服务端同样要拦）", async () => {
    await expect(api.archiveRole("R1")).rejects.toThrow("内置角色不可归档");
  });

  it("归档不存在的记录直接报错，不静默成功", async () => {
    await expect(api.archiveBank("NO_SUCH_BANK")).rejects.toThrow("记录不存在");
  });
});

describe("G2 导入：设备台账", () => {
  it("新机柜号新增、已存在机柜号更新（幂等重导不产生重复行）", async () => {
    const before = (await api.listCabinets({ size: 999 })).total;
    const r1 = await api.importCabinets([
      { cabinetNo: "CAB9001", sn: "SN99001", vendorCode: "cd-tech", model: "X6", slotTotal: 8 },
    ]);
    expect(r1).toEqual({ imported: 1, updated: 0 });
    expect((await api.listCabinets({ size: 999 })).total).toBe(before + 1);

    const r2 = await api.importCabinets([{ cabinetNo: "CAB9001", model: "S8" }]);
    expect(r2).toEqual({ imported: 0, updated: 1 });
    expect((await api.listCabinets({ size: 999 })).total).toBe(before + 1);
    expect((await api.getCabinet("CAB9001")).cabinet.model).toBe("S8");
  });
});
