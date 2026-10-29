"use client";

// 库存余额（OM03-08）：当前有多少 —— 按仓库 / 商家 / 型号汇总柜机与充电宝的各状态数量。
//
// 由两个既有读端点在页内汇总（listCabinets / listPowerbanks，全量拉取），不新增后端端点：
// 资产只由设备模块记账，这里就是台账口径的计数。**设备上报的可借数不冲抵资产库存** ——
// 一台在用柜机里有几颗宝是在线运行的事，不改变它们作为资产的状态与归属。
//
// 充电宝的「商家」取所在柜机的归属：宝本身不挂商家，它在谁的柜子里就随谁。
// 不在柜里的（在库、借出、遗失）没有柜机可依，单列一行。
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Notice } from "@/components/ui/notice";
import { useCan } from "@/lib/hooks/use-can";
import { exportCsv } from "@/lib/export-csv";
import { CAB_STATUS, PB_STATUS } from "./device-maps";
import type { Cabinet, CabinetStatus, Powerbank, PowerbankStatus } from "@/lib/types";
import { tally, totalRow, type StockRow as Row } from "@/lib/stock-tally";
import { StockStagesTable } from "./stock-stages";

type Kind = "CABINET" | "POWERBANK";
type Dim = "WAREHOUSE" | "OWNER" | "MODEL";
const DIM_LABEL: Record<Dim, string> = { WAREHOUSE: "按仓库", OWNER: "按商家", MODEL: "按厂商 / 型号" };
const CAB_KEYS = Object.keys(CAB_STATUS) as CabinetStatus[];
const PB_KEYS = Object.keys(PB_STATUS) as PowerbankStatus[];

/** 库存余额：按状态汇总（台账口径）/ 按生命周期阶段（S6 统一视图）。 */
export function StockBalanceTab() {
  const [view, setView] = useState<"STATUS" | "STAGE">("STATUS");
  return (
    <div>
      <div className="mb-3">
        <FilterSelect aria-label="视图" value={view} onChange={(v) => setView(v as "STATUS" | "STAGE")}
          options={[{ value: "STATUS", label: "按状态汇总" }, { value: "STAGE", label: "按生命周期阶段" }]} />
      </div>
      {view === "STATUS" ? <StatusBalance /> : <StockStagesTable />}
    </div>
  );
}

function StatusBalance() {
  const allow = useCan();
  const canCab = allow("device:cabinet:read");
  const canPb = allow("device:powerbank:read");
  const [kind, setKind] = useState<Kind>(canCab || !canPb ? "CABINET" : "POWERBANK");
  const [dim, setDim] = useState<Dim>("WAREHOUSE");

  // 充电宝按商家汇总时要查它所在柜机的归属，所以两类资产都可能要拉柜机
  const cabsQ = useQuery({
    queryKey: ["cabinets", "stock-all"],
    queryFn: () => api.listCabinets({ page: 1, size: UNPAGED_SIZE }),
    enabled: canCab,
  });
  const pbsQ = useQuery({
    queryKey: ["powerbanks", "stock-all"],
    queryFn: () => api.listPowerbanks({ page: 1, size: UNPAGED_SIZE }),
    enabled: canPb && kind === "POWERBANK",
  });

  const cabinetOwner = useMemo(
    () => new Map((cabsQ.data?.list ?? []).map((c) => [c.cabinetNo, c.agentNo])),
    [cabsQ.data],
  );

  const rows: Row[] = useMemo(() => {
    if (kind === "CABINET") {
      const list = (cabsQ.data?.list ?? []).filter((c) => !c.archivedAt);
      const keyOf = (c: Cabinet) =>
        dim === "WAREHOUSE" ? c.warehouseNo ?? "未登记仓库"
        : dim === "OWNER" ? c.agentNo ?? "平台直营"
        : `${c.vendorCode} · ${c.model}`;
      return tally(list, keyOf, (c) => c.status);
    }
    const list = (pbsQ.data?.list ?? []).filter((p) => !p.archivedAt);
    const keyOf = (p: Powerbank) =>
      dim === "WAREHOUSE" ? p.warehouseNo ?? "未登记仓库"
      : dim === "OWNER" ? (p.cabinetNo ? cabinetOwner.get(p.cabinetNo) ?? "平台直营" : "不在柜内")
      : p.vendorCode ?? "未登记厂商";
    return tally(list, keyOf, (p) => p.status);
  }, [kind, dim, cabsQ.data, pbsQ.data, cabinetOwner]);

  const withTotal = rows.length ? [...rows, totalRow(rows)] : rows;
  const statusKeys: string[] = kind === "CABINET" ? CAB_KEYS : PB_KEYS;
  const statusLabel = (k: string) => (kind === "CABINET" ? CAB_STATUS[k as CabinetStatus] : PB_STATUS[k as PowerbankStatus]).label;
  const dimHeader = dim === "WAREHOUSE" ? "仓库" : dim === "OWNER" ? "商家" : "厂商 / 型号";

  const cols: Column<Row>[] = [
    { header: dimHeader, cell: (r) => (r.key === "合计" ? <span className="txt-strong">合计</span> : <span className="tabular-nums">{r.key}</span>) },
    { header: "合计", className: "text-right", cell: (r) => <span className="txt-strong tabular-nums">{r.total}</span> },
    ...statusKeys.map((k) => ({
      header: statusLabel(k),
      className: "text-right",
      cell: (r: Row) => r.counts[k]
        ? <span className="tabular-nums">{r.counts[k]}</span>
        : <span className="text-muted-foreground">0</span>,
    })),
  ];

  const q = kind === "CABINET" ? cabsQ : pbsQ;
  const missing = kind === "CABINET" ? !canCab : !canPb;

  return (
    <>
      <Toolbar
        onExport={withTotal.length ? () => exportCsv<Row>(`库存余额-${kind === "CABINET" ? "柜机" : "充电宝"}-${DIM_LABEL[dim]}`, [
          { header: dimHeader, value: (r) => r.key },
          { header: "合计", value: (r) => r.total },
          ...statusKeys.map((k) => ({ header: statusLabel(k), value: (r: Row) => r.counts[k] ?? 0 })),
        ], withTotal) : undefined}
      >
        <FilterSelect
          aria-label="资产类型"
          value={kind}
          onChange={(v) => setKind(v as Kind)}
          options={[{ value: "CABINET", label: "柜机" }, { value: "POWERBANK", label: "充电宝" }]}
        />
        <FilterSelect
          aria-label="汇总维度"
          value={dim}
          onChange={(v) => setDim(v as Dim)}
          options={(Object.keys(DIM_LABEL) as Dim[]).map((d) => ({ value: d, label: DIM_LABEL[d] }))}
        />
      </Toolbar>
      <p className="mb-3 txt-caption text-muted-foreground">
        台账口径的当前数量（不含已归档），设备上报的可借数不冲抵资产库存。
        {kind === "POWERBANK" && dim === "OWNER" && " 充电宝的商家取所在柜机的归属。"}
      </p>
      {missing ? (
        <Notice>
          查看{kind === "CABINET" ? "柜机" : "充电宝"}库存需要 {kind === "CABINET" ? "device:cabinet:read" : "device:powerbank:read"}，当前角色没有。
        </Notice>
      ) : (
        <DataTable
          rowKey={(r: Row) => r.key}
          columns={cols}
          rows={withTotal}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty={`还没有${kind === "CABINET" ? "柜机" : "充电宝"}入账——新设备经收货入库或期初登记后出现在这里`}
        />
      )}
    </>
  );
}
