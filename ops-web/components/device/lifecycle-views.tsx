"use client";

// 设备生命周期的五个视图（docs/requirements/设备生命周期管理-运营端设计.md §五、§六 A2 ~ A4）。
// 全部是既有数据的设备视角，不新增后端端点：
//   部署与装机（装机工单）· 待上线（已绑定未上线 + 上线门禁）·
//   故障与维修（故障柜 / 故障宝 + 维修工单）· 撤机与回仓（撤机工单 + 回仓调拨）· 报废与遗失。
// 装机 / 撤机工单仍在运维管理 › 工单里办理；这里是同一批工单按设备看，不是第二份数据。
import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { SummaryCard } from "@/components/ui/summary-card";
import { StatusBadge } from "@/components/ui/status-badge";
import { GateChecklist } from "@/components/gate-checklist";
import { RefLink } from "@/components/ref-link";
import { WoStatusBadge, CabinetStatusBadge } from "@/components/status";
import { PB_STATUS } from "./device-maps";
import type { Cabinet, Powerbank, WorkOrder, InventoryTransfer } from "@/lib/types";

/** 未完结 = 还没完工（完工 DONE、复核 AUDITED、关闭 CLOSED 之外都算在途）。 */
const isOpenWo = (w: WorkOrder) => !["DONE", "AUDITED", "CLOSED"].includes(w.status);

const cabLink = (no: string | null) => no
  ? <Link href={`/devices/detail?no=${no}`} className="txt-strong tabular-nums hover:underline">{no}</Link>
  : <span className="text-muted-foreground">-</span>;

/** 某类工单的全量列表（设备视角用，量级是运营台的在途工单，不分页）。 */
function useWorkOrders(type: string) {
  return useQuery({ queryKey: ["work-orders", "by-type", type], queryFn: () => api.listWorkOrders({ type, size: UNPAGED_SIZE }) });
}
function useCabinets(status?: string) {
  return useQuery({ queryKey: ["cabinets", "lifecycle", status ?? "all"], queryFn: () => api.listCabinets({ status, size: UNPAGED_SIZE }) });
}

function woCols(): Column<WorkOrder>[] {
  return [
    { header: "工单号", cell: (w) => <RefLink kind="wo" no={w.woNo} /> },
    { header: "柜机", cell: (w) => cabLink(w.cabinetNo) },
    { header: "站点", cell: (w) => <span className="text-muted-foreground">{w.locationName ?? "-"}</span> },
    { header: "状态", cell: (w) => <WoStatusBadge s={w.status} /> },
    { header: "处理人", cell: (w) => w.assigneeName ?? <span className="text-muted-foreground">待派</span> },
    { header: "创建", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.createdAt)}</span> },
  ];
}

// ————————————————————————————— 部署与装机 —————————————————————————————

/** 部署与装机：装机工单按「未完结 / 已完结」看。绑定时可同时生成装机工单（出库池）。 */
export function InstallsTab() {
  const q = useWorkOrders("INSTALL");
  const [scope, setScope] = useState<"OPEN" | "DONE" | "">("OPEN");
  const rows = (q.data?.list ?? []).filter((w) => !scope || (scope === "OPEN" ? isOpenWo(w) : !isOpenWo(w)));
  const open = (q.data?.list ?? []).filter(isOpenWo).length;
  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        装机工单由出库池的「绑定」生成（勾选「同时建装机工单」），也可在运维管理 › 工单里手工新建。
        完工时现场扫码确认点位，之后试借还、过上线门禁才上线 —— 门禁卡在哪一项看「待上线」。
      </Notice>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard label="未完结装机单" value={String(open)} sub="待派 / 处理中" />
        <SummaryCard label="已完结" value={String((q.data?.list.length ?? 0) - open)} sub="完工后进入待上线" />
      </div>
      <div className="flex gap-2">
        <FilterSelect aria-label="完结情况" value={scope} onChange={(v) => setScope(v as typeof scope)}
          options={[{ value: "OPEN", label: "未完结" }, { value: "DONE", label: "已完结" }]} allLabel="全部" />
      </div>
      <DataTable rowKey={(w: WorkOrder) => w.woNo} columns={woCols()} rows={q.data ? rows : undefined}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="没有装机工单 —— 在出库池绑定设备时勾选「同时建装机工单」即会生成" />
    </div>
  );
}

// ————————————————————————————— 待上线 —————————————————————————————

/** 待上线：已绑定点位、还在库的柜子；展开看上线门禁逐项，通过即可上线。 */
export function PendingLiveTab() {
  const qc = useQueryClient();
  const canGoLive = useCan()("device:cabinet:update");
  const q = useCabinets("IN_STOCK");
  const rows = (q.data?.list ?? []).filter((c) => c.locationNo && !c.archivedAt);
  const live = useMutation({
    mutationFn: (no: string) => api.goLive(no),
    onSuccess: (c) => {
      notify.success(`${c.cabinetNo} 已上线`);
      qc.invalidateQueries({ queryKey: ["cabinets"] });
      qc.invalidateQueries({ queryKey: ["go-live-gate"] });
    },
  });
  const cols: Column<Cabinet>[] = [
    { header: "柜机", cell: (c) => cabLink(c.cabinetNo) },
    { header: "商家", cell: (c) => c.agentNo ? <RefLink kind="agent" no={c.agentNo} /> : <span className="text-muted-foreground">平台直营</span> },
    { header: "站点 › 点位", cell: (c) => <span><RefLink kind="site" no={c.siteNo!} label={c.locationName ?? c.siteNo!} /> <span className="text-muted-foreground tabular-nums">› {c.locationNo}</span></span> },
    { header: "在线", cell: (c) => <span className={c.onlineStatus === "ONLINE" ? "" : "text-muted-foreground"}>{c.onlineStatus === "ONLINE" ? "在线" : "离线"}</span> },
    { header: "操作", cell: (c) => canGoLive
      ? <Button size="sm" disabled={live.isPending} onClick={() => live.mutate(c.cabinetNo)}>上线</Button>
      : <span className="text-muted-foreground">-</span> },
  ];
  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        已绑定点位、还没上线的设备。上线门禁 10 项（质检、勘测、装机工单、装宝比例、点位、站点营业、合同、在线、试借还、计费方案）
        由后端强制；其中合同与勘测一期跳过，由平台运营直接操作（系统参数 site.venue_gate.enabled 改 1 即恢复）。
        展开一行看卡在哪一项，每项都给了去处。门禁没过时点「上线」会被拒并说明原因。
      </Notice>
      <DataTable rowKey={(c: Cabinet) => c.cabinetNo} columns={cols} rows={q.data ? rows : undefined}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        expandable={(c) => <GateRow cabinetNo={c.cabinetNo} />}
        empty="没有待上线的设备 —— 设备从出库池绑定点位后出现在这里" />
    </div>
  );
}

function GateRow({ cabinetNo }: { cabinetNo: string }) {
  const g = useQuery({ queryKey: ["go-live-gate", cabinetNo], queryFn: () => api.goLiveGate(cabinetNo) });
  return (
    <GateChecklist data={g.data} loading={g.isLoading}
      passedHint="门禁全部通过，可以上线" blockedHint="还差 {n} 项，逐条处理后再上线" />
  );
}

// ————————————————————————————— 故障与维修 —————————————————————————————

/** 故障与维修：故障柜（带故障天数与在途维修工单）+ 故障充电宝。 */
export function RepairsTab() {
  const cabQ = useCabinets("FAULT");
  const pbQ = useQuery({ queryKey: ["powerbanks", "lifecycle"], queryFn: () => api.listPowerbanks({ size: UNPAGED_SIZE }) });
  const woQ = useWorkOrders("FAULT");
  const openWoOf = useMemo(() => {
    const m = new Map<string, WorkOrder>();
    for (const w of woQ.data?.list ?? []) if (w.cabinetNo && isOpenWo(w) && !m.has(w.cabinetNo)) m.set(w.cabinetNo, w);
    return m;
  }, [woQ.data]);
  const faultPbs = (pbQ.data?.list ?? []).filter((p) => p.status === "FAULT" && !p.archivedAt);
  const cabCols: Column<Cabinet>[] = [
    { header: "柜机", cell: (c) => cabLink(c.cabinetNo) },
    { header: "站点", cell: (c) => c.siteNo ? <RefLink kind="site" no={c.siteNo} label={c.locationName ?? c.siteNo} /> : <span className="text-muted-foreground">-</span> },
    { header: "状态", cell: (c) => <CabinetStatusBadge s={c.status} /> },
    { header: "维修工单", cell: (c) => {
      const w = openWoOf.get(c.cabinetNo);
      return w ? <span className="inline-flex items-center gap-1.5"><RefLink kind="wo" no={w.woNo} /><WoStatusBadge s={w.status} /></span>
        : <span className="text-destructive-ink">无在途维修单</span>;
    } },
    { header: "最后心跳", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.lastHeartbeatAt)}</span> },
  ];
  const pbCols: Column<Powerbank>[] = [
    { header: "充电宝", cell: (p) => <span className="tabular-nums">{p.powerbankNo}</span> },
    { header: "所在柜机", cell: (p) => cabLink(p.cabinetNo) },
    { header: "状态", cell: (p) => <StatusBadge map={PB_STATUS} value={p.status} /> },
  ];
  const noWo = (cabQ.data?.list ?? []).filter((c) => !openWoOf.has(c.cabinetNo)).length;
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard label="故障柜" value={String(cabQ.data?.list.length ?? 0)} sub="标故障后停借" />
        <SummaryCard label="无在途维修单" value={String(noWo)} sub="故障了但没人在修" />
        <SummaryCard label="故障充电宝" value={String(faultPbs.length)} sub="不出借" />
      </div>
      <Notice className="mb-0">
        「维修中」与「损坏」今天合并为故障，拆分待运营方裁决（设计 §七 #3）。修复在设备详情点「修复完成」；修不好走撤机回仓。
      </Notice>
      <div className="txt-strong">故障柜</div>
      <DataTable rowKey={(c: Cabinet) => c.cabinetNo} columns={cabCols} rows={cabQ.data?.list}
        loading={cabQ.isLoading} error={cabQ.error} onRetry={cabQ.refetch} empty="没有故障柜" />
      <div className="txt-strong">故障充电宝</div>
      <DataTable rowKey={(p: Powerbank) => p.powerbankNo} columns={pbCols} rows={pbQ.data ? faultPbs : undefined}
        loading={pbQ.isLoading} error={pbQ.error} onRetry={pbQ.refetch} empty="没有故障充电宝" />
    </div>
  );
}

// ————————————————————————————— 撤机与回仓 —————————————————————————————

/** 撤机与回仓：撤机工单 + 从站点回仓的调拨单（撤机完工自动生成）。 */
export function RemovalsTab() {
  const woQ = useWorkOrders("REMOVE");
  const trQ = useQuery({ queryKey: ["inventory-transfers", "returns"], queryFn: () => api.listInventoryTransfers({ size: UNPAGED_SIZE }) });
  const returns = (trQ.data?.list ?? []).filter((t) => t.fromType === "SITE" && t.toType === "WAREHOUSE");
  const trCols: Column<InventoryTransfer>[] = [
    { header: "调拨单", cell: (t) => <Link href={`/devices?tab=inventory&no=${t.transferNo}`} className="tabular-nums hover:underline">{t.transferNo}</Link> },
    { header: "从", cell: (t) => t.fromLocation },
    { header: "回到", cell: (t) => t.toLocation },
    { header: "状态", cell: (t) => <span>{t.status === "DONE" ? "已签收" : t.status === "IN_TRANSIT" ? "在途" : "草稿"}</span> },
  ];
  const openN = (woQ.data?.list ?? []).filter(isOpenWo).length;
  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        撤机单的去向：「回出库池」= 同一商家换点位 / 换站点，柜子留在商家名下、不生成回仓单；
        「回仓」= 退出、维修、返厂，完工后自动生成回仓调拨单，签收后离开商家。完工须拍照并清点充电宝数，少了落资产差异。
      </Notice>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard label="未完结撤机单" value={String(openN)} sub="待派 / 处理中" />
        <SummaryCard label="回仓调拨" value={String(returns.length)} sub={`在途 ${returns.filter((t) => t.status !== "DONE").length}`} />
      </div>
      <div className="txt-strong">撤机工单</div>
      <DataTable rowKey={(w: WorkOrder) => w.woNo} columns={woCols()} rows={woQ.data?.list}
        loading={woQ.isLoading} error={woQ.error} onRetry={woQ.refetch} empty="没有撤机工单" />
      <div className="txt-strong">回仓调拨</div>
      <DataTable rowKey={(t: InventoryTransfer) => t.transferNo} columns={trCols} rows={trQ.data ? returns : undefined}
        loading={trQ.isLoading} error={trQ.error} onRetry={trQ.refetch} empty="没有从站点回仓的调拨单" />
    </div>
  );
}

// ————————————————————————————— 报废与遗失 —————————————————————————————

/** 报废与遗失：已报废的柜子，报废 / 遗失 / 售出的充电宝。 */
export function RetiredTab() {
  const cabQ = useCabinets("RETIRED");
  const pbQ = useQuery({ queryKey: ["powerbanks", "lifecycle"], queryFn: () => api.listPowerbanks({ size: UNPAGED_SIZE }) });
  const gone = (pbQ.data?.list ?? []).filter((p) => ["SCRAP", "LOST", "SOLD"].includes(p.status));
  const cabCols: Column<Cabinet>[] = [
    { header: "柜机", cell: (c) => cabLink(c.cabinetNo) },
    { header: "SN / 型号", cell: (c) => <span className="tabular-nums">{c.sn}<span className="text-muted-foreground"> · {c.model}</span></span> },
    { header: "状态", cell: (c) => <CabinetStatusBadge s={c.status} /> },
  ];
  const pbCols: Column<Powerbank>[] = [
    { header: "充电宝", cell: (p) => <span className="tabular-nums">{p.powerbankNo}</span> },
    { header: "状态", cell: (p) => <StatusBadge map={PB_STATUS} value={p.status} /> },
    { header: "最后所在柜", cell: (p) => cabLink(p.cabinetNo) },
  ];
  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        报废不可逆，在设备详情走「报废」并写原因。机柜是否需要「遗失」状态待运营方裁决（设计 §七 #4）；充电宝的遗失经「疑似丢失 → 人工确认」。
      </Notice>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard label="报废柜" value={String(cabQ.data?.list.length ?? 0)} sub="不再出库或上线" />
        <SummaryCard label="报废 / 遗失 / 售出宝" value={String(gone.length)} sub={`遗失 ${gone.filter((p) => p.status === "LOST").length}`} />
      </div>
      <div className="txt-strong">报废柜</div>
      <DataTable rowKey={(c: Cabinet) => c.cabinetNo} columns={cabCols} rows={cabQ.data?.list}
        loading={cabQ.isLoading} error={cabQ.error} onRetry={cabQ.refetch} empty="没有报废的柜子" />
      <div className="txt-strong">报废 / 遗失 / 售出的充电宝</div>
      <DataTable rowKey={(p: Powerbank) => p.powerbankNo} columns={pbCols} rows={pbQ.data ? gone : undefined}
        loading={pbQ.isLoading} error={pbQ.error} onRetry={pbQ.refetch} empty="没有报废、遗失或售出的充电宝" />
    </div>
  );
}

