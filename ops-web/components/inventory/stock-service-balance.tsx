"use client";

// 设备库存余额 · 库存账口径（对接明细 B01）：数量取独立进销存（库存服务），不再拿本地设备台账当库存账。
// 在手 = 占用 + 不可用（冻结、待检等）+ 可用。库存服务查不到时表格报错，不显示 0。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { DeviceBalance, DeviceSerial, IntakeItemType } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Drawer } from "@/components/ui/drawer";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { exportCsv } from "@/lib/export-csv";

const TYPE_LABEL: Record<IntakeItemType, string> = { CABINET: "柜机", POWERBANK: "充电宝" };
type Row = DeviceBalance & { total?: boolean };

export function ServiceBalance() {
  const [kind, setKind] = useState<IntakeItemType>("CABINET");
  const [drill, setDrill] = useState<DeviceBalance | null>(null);
  const q = useQuery({ queryKey: ["device-balances", kind], queryFn: () => api.listDeviceBalances({ itemType: kind }) });
  const rows = q.data ?? [];
  const sum = (k: "onHand" | "reserved" | "unavailable" | "available") => rows.reduce((n, r) => n + r[k], 0);
  const withTotal: Row[] = rows.length
    ? [...rows, { warehouseNo: "合计", warehouseName: "合计", itemType: kind, onHand: sum("onHand"), reserved: sum("reserved"),
      unavailable: sum("unavailable"), available: sum("available"), total: true }]
    : rows;

  const num = (n: number) => (n ? <span className="tabular-nums">{n}</span> : <span className="text-muted-foreground">0</span>);
  const cols: Column<Row>[] = [
    { header: "仓库", cell: (r) => (r.total ? <span className="txt-strong">合计</span> : r.warehouseName) },
    { header: "在手", className: "text-right", cell: (r) => (r.total || !r.onHand
      ? <span className="txt-strong tabular-nums">{r.onHand}</span>
      : <button type="button" className="tabular-nums text-primary hover:underline" onClick={() => setDrill(r)}>{r.onHand}</button>) },
    { header: "占用", className: "text-right", cell: (r) => num(r.reserved) },
    { header: "不可用", className: "text-right", cell: (r) => num(r.unavailable) },
    { header: "可用", className: "text-right", cell: (r) => num(r.available) },
  ];

  return (
    <>
      <Toolbar
        onExport={rows.length ? () => exportCsv<Row>(`库存余额-库存账-${TYPE_LABEL[kind]}`, [
          { header: "仓库", value: (r) => r.warehouseName },
          { header: "在手", value: (r) => r.onHand },
          { header: "占用", value: (r) => r.reserved },
          { header: "不可用", value: (r) => r.unavailable },
          { header: "可用", value: (r) => r.available },
        ], withTotal) : undefined}
      >
        <FilterSelect aria-label="设备类型" value={kind} onChange={(v) => setKind(v as IntakeItemType)}
          options={[{ value: "CABINET", label: "柜机" }, { value: "POWERBANK", label: "充电宝" }]} />
      </Toolbar>
      <p className="mb-3 txt-caption text-muted-foreground">
        库存账口径：数量来自独立进销存。在手 = 占用 + 不可用（冻结、待检等）+ 可用；只含在仓的设备，已出库、投放中的不在这里。点在手数看序列号。
      </p>
      <DataTable rowKey={(r: Row) => r.warehouseNo} columns={cols} rows={withTotal} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={`还没有${TYPE_LABEL[kind]}入账——新设备经收货入库或期初登记后出现在这里`} />
      <SerialDrawer target={drill} onClose={() => setDrill(null)} />
    </>
  );
}

function SerialDrawer({ target, onClose }: { target: DeviceBalance | null; onClose: () => void }) {
  const paging = usePaging();
  const q = useQuery({
    queryKey: ["device-serials", target?.warehouseNo, target?.itemType, paging.page, paging.size], enabled: !!target,
    queryFn: () => api.listDeviceSerials({ warehouseNo: target!.warehouseNo, itemType: target!.itemType, page: paging.page, size: paging.size }),
  });
  const cols: Column<DeviceSerial>[] = [{ header: "序列号", cell: (s) => <span className="font-mono">{s.serialNo}</span> }];
  return (
    <Drawer open={!!target} onOpenChange={(o) => { if (!o) { paging.setPage(1); onClose(); } }}
      title={target ? `${target.warehouseName} · 在库${TYPE_LABEL[target.itemType]}` : ""}>
      <DataTable rowKey={(s: DeviceSerial) => s.serialNo} columns={cols} rows={q.data?.list} loading={q.isLoading} error={q.error}
        onRetry={q.refetch} empty="没有在库序列号" />
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
    </Drawer>
  );
}
