"use client";

// 库存调拨（库存管理 › 库内作业，V209 自设备页拆出）：仓库之间、仓库与站点之间搬设备，先建单，发出与签收逐件核对。
// 支持 `?no=` 深链（RefLink transfer → /inventory?tab=transfers&no=），关抽屉时把参数清掉。
import { useEffect, useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import type { InventoryTransfer, InvTransferReq } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { fmtTime } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import { Toolbar } from "@/components/ui/toolbar";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { TRANSFER_STATUS } from "@/components/device/device-maps";
import { TransferForm, TransferDetailDrawer } from "@/components/inventory/transfer";

export function TransfersTab() {
  const canEdit = useCan()("device:inventory:transfer");
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [form, setForm] = useState<Partial<InvTransferReq> | null>(null);
  const sp = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const [detail, setDetailState] = useState<string | null>(() => sp.get("no"));
  const setDetail = (no: string | null) => {
    setDetailState(no);
    if (!no && sp.get("no")) {
      const q = new URLSearchParams(sp.toString());
      q.delete("no");
      router.replace(`${pathname}?${q.toString()}`, { scroll: false });
    }
  };
  useEffect(() => { const n = sp.get("no"); if (n) setDetailState(n); }, [sp]);

  const q = useQuery({
    queryKey: ["inventory-transfers", paging.page, paging.size, keyword],
    queryFn: () => api.listInventoryTransfers({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
  });

  // 调拨列表只说得出「从哪到哪、多少台」；盘点对不上要查的是「具体哪几台」，在详情里 —— 故单号做成详情入口
  const cols: Column<InventoryTransfer>[] = [
    { header: "调拨单号", cell: (r) => (
      <button type="button" className="font-medium tabular-nums underline-offset-4 hover:underline" onClick={() => setDetail(r.transferNo)}>{r.transferNo}</button>
    ) },
    // 名字是给人看的，编号才说得清「到底去了哪个站点 / 仓库」
    { header: "调出", cell: (r) => (
      <div className="min-w-0">
        <div className="truncate">{r.fromLocation}</div>
        {r.fromRef ? <div className="truncate txt-caption text-muted-foreground tabular-nums">{r.fromRef}</div> : null}
      </div>
    ) },
    { header: "调入", cell: (r) => (
      <div className="min-w-0">
        <div className="truncate">{r.toLocation}</div>
        {r.toRef ? <div className="truncate txt-caption text-muted-foreground tabular-nums">{r.toRef}</div> : null}
      </div>
    ) },
    { header: "充电宝数", cell: (r) => <span className="tabular-nums">{Math.round(r.powerbankCount)}</span> },
    { header: "状态", cell: (r) => <StatusBadge map={TRANSFER_STATUS} value={r.status} /> },
    { header: "操作人", cell: (r) => <span className="text-muted-foreground">{r.operator}</span> },
    { header: "创建时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
    { header: "操作", cell: (r) => (
      <Button size="sm" variant="outline" onClick={() => setDetail(r.transferNo)}>
        {/* 动作按状态给名字：草稿要设明细发出、在途要签收，完成的只剩查看 */}
        {!canEdit ? "查看" : r.status === "DRAFT" ? "明细 / 发出" : r.status === "IN_TRANSIT" ? "签收" : "查看"}
      </Button>
    ) },
  ];

  const onExport = () => exportCsv<InventoryTransfer>("库存调拨", [
    { header: "调拨单号", value: (r) => r.transferNo },
    { header: "调出点位", value: (r) => r.fromLocation },
    { header: "调入点位", value: (r) => r.toLocation },
    { header: "充电宝数", value: (r) => Math.round(r.powerbankCount) },
    { header: "状态", value: (r) => TRANSFER_STATUS[r.status]?.label ?? r.status },
    { header: "操作人", value: (r) => r.operator },
    { header: "创建时间", value: (r) => r.createdAt },
  ], q.data?.list ?? []);

  return (
    <div>
      {!canEdit && <ReadOnlyNotice what="调拨执行" perm="device:inventory:transfer" note="不能建单、发出或签收" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索调拨单号 / 点位"
        onAdd={() => setForm({ itemType: "CABINET", fromType: "WAREHOUSE", fromRef: "", toType: "SITE", toRef: "" })}
        addLabel="新增调拨单" canAdd={canEdit} onExport={q.data?.list?.length ? onExport : undefined} />
      <PagedTable query={q} paging={paging} rowKey={(r: InventoryTransfer) => r.transferNo} columns={cols}
        empty={keyword ? "没有匹配的调拨单。" : "还没有调拨单——仓库之间、仓库与站点之间搬设备前先建单，发出与签收都按单逐件核对"} />
      <TransferForm value={form} onClose={() => setForm(null)} />
      <TransferDetailDrawer transferNo={detail} onClose={() => setDetail(null)} onEdit={(t) => setForm(t)} />
    </div>
  );
}
