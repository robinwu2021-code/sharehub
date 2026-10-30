"use client";

// 库存管理（V209，2026-10-29 自设备管理拆出）：主数据 → 出入库 → 库内作业 → 库存账 → 设置。
// 管「东西在不在库、在哪个仓、有多少」：设备（柜 / 宝 / 其他整机，逐件）与物料（备件、耗材，按数量）同一套仓库与单据。
// 设备上线后的生命周期（装机、监控、维修、撤机、报废）留在设备管理。将来的独立系统以 ai-shop 库存服务为准。
// 菜单规范：docs/technical/设计规范-库存管理菜单.md；页签键与菜单叶子一一对应（lib/inventory-routes.ts）。
import { Suspense } from "react";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { MaterialsTab } from "@/components/inventory/materials";
import { WarehousesTab, InventorySettingsTab } from "@/components/inventory/warehouses";
import { SuppliersTab } from "@/components/inventory/suppliers";
import { IntakesTab } from "@/components/inventory/intakes";
import { MaterialDocsTab } from "@/components/inventory/material-docs";
import { IssuedPoolTab } from "@/components/inventory/issued-pool";
import { DisposalsTab } from "@/components/inventory/disposals";
import { TransfersTab } from "@/components/inventory/transfers-tab";
import { StockCountsTab } from "@/components/inventory/stock-counts";
import { AssetDiffsTable } from "@/components/inventory/transfer";
import { StockBalanceTab } from "@/components/inventory/stock-balance";
import { MaterialStockTab } from "@/components/inventory/material-stock";
import { StockLedgerTab } from "@/components/inventory/stock-ledger";

// 与菜单同序（nav-page-tabs.test 从本文件解析这个数组，须是模块级 as const 字面量；
// 与 lib/inventory-routes.ts 的 INVENTORY_TAB_KEYS 一致，由 inventory-routes.test 对菜单兜住）
const TAB_KEYS = ["materials", "warehouses", "suppliers", "intakes", "material-docs", "issued", "disposals",
  "transfers", "counts", "asset-diffs", "stock", "material-stock", "ledger", "settings"] as const;
const DEFAULT_TAB = "stock";

function InventoryInner() {
  const tabs = useNavTabs("/inventory", TAB_KEYS, DEFAULT_TAB);
  const { tab, setTab } = usePageTab(tabs, undefined, { defaultKey: DEFAULT_TAB });
  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "materials" && <MaterialsTab />}
      {tab === "warehouses" && <WarehousesTab />}
      {tab === "suppliers" && <SuppliersTab />}
      {tab === "intakes" && <IntakesTab />}
      {tab === "material-docs" && <MaterialDocsTab />}
      {tab === "issued" && <IssuedPoolTab />}
      {tab === "disposals" && <DisposalsTab />}
      {tab === "transfers" && <TransfersTab />}
      {tab === "counts" && <StockCountsTab />}
      {tab === "asset-diffs" && <AssetDiffsTable />}
      {tab === "stock" && <StockBalanceTab />}
      {tab === "material-stock" && <MaterialStockTab />}
      {tab === "ledger" && <StockLedgerTab />}
      {tab === "settings" && <InventorySettingsTab />}
    </div>
  );
}

export default function InventoryPage() {
  return <Suspense fallback={null}><InventoryInner /></Suspense>;
}
