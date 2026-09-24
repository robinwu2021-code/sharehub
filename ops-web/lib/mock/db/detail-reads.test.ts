import { describe, it, expect } from "vitest";
import { mockApi } from "../../api/mock";

/**
 * 两个**详情比列表多出东西**的读接口（见《后端已就绪但未接的能力》A1/A2）。
 *
 * 列表类型里根本没有这两层：调拨单没有明细行、发票没有对应订单号。
 * 于是运营点不开「具体哪几台」「这张票开的是哪几笔」——
 * 而盘点差异和税务质疑要答的恰恰是这两个问题。
 */
describe("调拨单详情", () => {
  it("明细条数必须等于单据台数", async () => {
    // 对不上就是「单据说 12 台、明细只有 9 行」这种没人说得清的差异
    const { list } = await mockApi.listInventoryTransfers({ page: 1, size: 20 });
    for (const t of list) {
      const d = await mockApi.getInventoryTransfer(t.transferNo);
      expect(d.items.length, `${t.transferNo} 明细条数`).toBe(t.powerbankCount);
    }
  });

  it("同一单里不许出现两行同号", async () => {
    // 同号的两行在盘点表上无法区分，「核对过的是哪一台」就答不了
    const { list } = await mockApi.listInventoryTransfers({ page: 1, size: 20 });
    for (const t of list) {
      const items = (await mockApi.getInventoryTransfer(t.transferNo)).items;
      expect(new Set(items.map((i) => i.itemNo)).size, `${t.transferNo} 去重后`).toBe(items.length);
    }
  });

  it("已完成的单视为全部核对过", async () => {
    const { list } = await mockApi.listInventoryTransfers({ page: 1, size: 50 });
    const done = list.find((t) => t.status === "DONE");
    expect(done, "前提：种子里有已完成的调拨单").toBeTruthy();
    const items = (await mockApi.getInventoryTransfer(done!.transferNo)).items;
    expect(items.every((i) => i.checked)).toBe(true);
  });

  it("查不到的单号要报错，而不是给一张空明细", async () => {
    // 空明细会被当成「这单真的没调东西」，而实际是单号敲错了
    await expect(mockApi.getInventoryTransfer("TR-NOT-EXIST")).rejects.toThrow();
  });
});

describe("发票详情", () => {
  it("带出对应的订单号且不重复", async () => {
    const { list } = await mockApi.listInvoices({ page: 1, size: 20 });
    expect(list.length, "前提：种子里有发票").toBeGreaterThan(0);
    const v = await mockApi.getInvoice(list[0].invoiceNo);
    expect(v.invoice.invoiceNo).toBe(list[0].invoiceNo);
    expect(new Set(v.orderNos).size, "同一笔订单不该列两次").toBe(v.orderNos.length);
  });

  it("查不到的票号要报错", async () => {
    await expect(mockApi.getInvoice("INV-NOT-EXIST")).rejects.toThrow();
  });
});
