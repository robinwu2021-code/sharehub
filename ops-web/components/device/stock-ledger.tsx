"use client";

// 库存流水（设备管理 › 仓储与出库，S4 / V199）：进仓 +、出仓 −。本地引擎取进出仓履历，独立进销存取它的流水。
import { useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { usePaging } from "@/lib/hooks/use-paging";
import { fmtTime } from "@/lib/utils";
import type { StockLedgerRow } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";

const ITEM_TYPES = [{ value: "CABINET", label: "机柜" }, { value: "POWERBANK", label: "充电宝" }];
const ITEM_LABEL: Record<string, string> = { CABINET: "机柜", POWERBANK: "充电宝" };
const DOC_LABEL: Record<string, string> = {
  INBOUND: "入库", ISSUE: "出库", POOL_RETURN: "出库池退回", SHIP: "调拨发货", RECEIVE: "调拨签收", RETIRE: "报废",
  BIND: "绑定出仓", UNBIND: "解绑回仓", IN: "入库单", OUT: "出库单",
};

export function StockLedgerTab() {
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [warehouseNo, setWarehouseNo] = useState("");
  const [itemType, setItemType] = useState("");
  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const whOptions = (whQ.data ?? []).map((w) => ({ value: w.warehouseNo, label: w.name }));
  const whName = (no: string | null) => (no ? whQ.data?.find((w) => w.warehouseNo === no)?.name ?? no : "-");
  const q = useQuery({
    queryKey: ["stock-ledger", paging.page, paging.size, keyword, warehouseNo, itemType],
    queryFn: () => api.listStockLedger({ page: paging.page, size: paging.size, keyword: keyword || undefined,
      warehouseNo: warehouseNo || undefined, itemType: itemType || undefined }),
    placeholderData: keepPreviousData,
  });

  const cols: Column<StockLedgerRow>[] = [
    { header: "时间", className: "whitespace-nowrap", cell: (r) => <span className="text-muted-foreground">{r.occurredAt ? fmtTime(r.occurredAt) : "-"}</span> },
    { header: "仓库", cell: (r) => whName(r.warehouseNo) },
    { header: "类型", cell: (r) => (r.itemType ? ITEM_LABEL[r.itemType] ?? r.itemType : "-") },
    { header: "设备号", cell: (r) => <span className="font-mono">{r.itemNo ?? "-"}</span> },
    { header: "动作", className: "whitespace-nowrap", cell: (r) => (r.docKind ? DOC_LABEL[r.docKind] ?? r.docKind : "-") },
    { header: "单号", cell: (r) => <span className="font-mono">{r.docNo ?? "-"}</span> },
    { header: "数量", className: "text-right tabular-nums", cell: (r) => (r.qtyDelta > 0 ? `+${r.qtyDelta}` : String(r.qtyDelta)) },
    { header: "经办", cell: (r) => <span className="text-muted-foreground">{r.operator ?? "-"}</span> },
  ];

  return (
    <div>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="设备号 / 单号">
        <FilterSelect aria-label="仓库" value={warehouseNo} onChange={(v) => { setWarehouseNo(v); paging.reset(); }} options={whOptions} allLabel="全部仓库" />
        <FilterSelect aria-label="类型" value={itemType} onChange={(v) => { setItemType(v); paging.reset(); }} options={ITEM_TYPES} allLabel="全部类型" />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(r: StockLedgerRow) => r.id} columns={cols}
        empty={keyword || warehouseNo || itemType ? "没有匹配的流水" : "暂无流水"} />
    </div>
  );
}
