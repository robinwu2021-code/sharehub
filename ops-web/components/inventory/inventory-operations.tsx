"use client";

// 库存执行异常（对接明细 F04 / F05）：没完成的库存写操作与它们在库存服务的步骤。
// 「查回」按步骤键问库存服务：查到 = 远端已执行（重试时直接复用），查不到 = 没执行；过了在途窗口才下结论。
// 「原样重试」只给原操作人：重走一遍原动作，不换键、不重建单据。系统每 5 分钟也会自动查回一次。
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InventoryOperation, InventoryOperationPhase } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { PAGE_SIZE } from "@/lib/constants";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";

const PHASE: StatusMap<InventoryOperationPhase> = {
  PREPARED: { label: "进行中", tone: "info" }, NEEDS_REVIEW: { label: "待复核", tone: "warning" },
  REJECTED: { label: "已拒绝", tone: "muted" }, COMPLETED: { label: "已完成", tone: "success" },
};
const KIND: Record<string, string> = {
  MATERIAL_DOC: "物料单据", INTAKE_INBOUND: "入库", DEVICE_ISSUE: "出库", DEVICE_CLAIM: "签收", DEVICE_REDIRECT: "出库调整",
  DEVICE_REVOKE: "出库撤销", TRANSFER_SHIP: "调拨发货", TRANSFER_RECEIVE: "调拨签收",
};
const STEP: Record<string, string> = { UNKNOWN: "结果不明", COMPLETED: "已执行", FAILED: "未执行", PENDING: "未发出" };

export function InventoryOperations() {
  const qc = useQueryClient();
  const canResolve = useCan()("device:inventory:transfer");
  const q = useQuery({ queryKey: ["inventory-operations"], queryFn: () => api.listInventoryOperations({ size: PAGE_SIZE }) });
  const done = (msg: string) => () => {
    notify.success(msg);
    qc.invalidateQueries({ queryKey: ["inventory-operations"] });
    qc.invalidateQueries({ queryKey: ["inventory-engine"] });
  };
  const resolve = useMutation({ mutationFn: (no: string) => api.resolveInventoryOperation(no), onSuccess: done("已向库存服务查回") });
  const retry = useMutation({ mutationFn: (no: string) => api.retryInventoryOperation(no), onSuccess: done("已原样重试") });

  const cols: Column<InventoryOperation>[] = [
    { header: "操作", cell: (o) => <span className="font-mono">{o.operationNo}</span> },
    { header: "类型", className: "whitespace-nowrap", cell: (o) => KIND[o.kind] ?? o.kind },
    { header: "状态", cell: (o) => <StatusBadge map={PHASE} value={o.phase} /> },
    { header: "库存服务步骤", cell: (o) => (o.steps.length
      ? <span className="txt-caption">{o.steps.map((s) => `${s.code} ${STEP[s.status] ?? s.status}`).join(" · ")}</span>
      : <span className="txt-caption text-muted-foreground">一步都没发出</span>) },
    { header: "原因", cell: (o) => <span className="txt-caption">{o.lastError ?? "-"}</span> },
    { header: "发起人", className: "whitespace-nowrap", cell: (o) => o.createdBy ?? "-" },
    { header: "更新", className: "whitespace-nowrap", cell: (o) => fmtTime(o.updatedAt) },
    { header: "", className: "whitespace-nowrap", cell: (o) => (
      <span className="inline-flex gap-1.5">
        {canResolve && o.steps.some((s) => s.status === "UNKNOWN") && (
          <Button size="sm" variant="outline" disabled={resolve.isPending} onClick={() => resolve.mutate(o.operationNo)}>查回</Button>)}
        {o.retryable && <Button size="sm" disabled={retry.isPending} onClick={() => retry.mutate(o.operationNo)}>原样重试</Button>}
      </span>) },
  ];
  return (
    <div className="mt-6">
      <div className="mb-1 txt-strong">执行异常</div>
      <p className="mb-3 txt-caption text-muted-foreground">
        没完成的库存操作。「查回」问库存服务每一步到底执行了没有（发出 2 分钟后才下结论，系统每 5 分钟也自动查一次）；
        远端已执行、本地没完成的，由原操作人「原样重试」补完，不会重复记账。
      </p>
      <DataTable rowKey={(o: InventoryOperation) => o.operationNo} columns={cols} rows={q.data?.list} loading={q.isLoading} error={q.error}
        onRetry={q.refetch} empty="没有未完成的库存操作" />
    </div>
  );
}
