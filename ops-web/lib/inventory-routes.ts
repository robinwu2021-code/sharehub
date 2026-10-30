// 库存管理（/inventory）的页签与旧链接改写（V209，2026-10-29 自设备管理拆出）。
//
// 一个 URL 只属于一个 L1（findActiveSection 按路径前缀定归属），所以库存页签整体从 /devices 搬到 /inventory。
// 旧链接仍散在收藏、已发出的通知与告警「去处理」里 —— 改写集中在这里，设备页调它，规则只写一处。
// 「库存调拨」页签键由 inventory 改为 transfers（/inventory?tab=inventory 读起来是废话），其余键不变。

/** 库存管理页签（菜单顺序：主数据 → 出入库 → 库内作业 → 库存账 → 设置）。 */
export const INVENTORY_TAB_KEYS = ["materials", "warehouses", "suppliers", "intakes", "material-docs", "issued", "disposals",
  "transfers", "counts", "asset-diffs", "stock", "material-stock", "ledger", "settings"] as const;
export type InventoryTab = (typeof INVENTORY_TAB_KEYS)[number];

/** 从 /devices 搬走的页签：旧键 → 新键。 */
const MOVED_FROM_DEVICES: Record<string, InventoryTab> = {
  warehouses: "warehouses", suppliers: "suppliers", intakes: "intakes", issued: "issued", disposals: "disposals",
  inventory: "transfers", counts: "counts", "asset-diffs": "asset-diffs", stock: "stock", ledger: "ledger",
};

/** /devices 上的库存页签链接该转到哪：返回目标 URL，不用转返回 null。其余参数（no 等）原样保留。 */
export function inventoryRedirect(sp: URLSearchParams): string | null {
  const tab = sp.get("tab");
  const to = tab ? MOVED_FROM_DEVICES[tab] : undefined;
  if (!to) return null;
  const q = new URLSearchParams(sp);
  q.set("tab", to);
  return `/inventory?${q.toString()}`;
}
