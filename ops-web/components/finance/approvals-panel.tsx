"use client";

// 资金操作审批待办（运营方原始需求 SEC-03，V189）。客服补偿 / 免单、手工凭证 ≥ 阈值时后端不执行、落一张待审批；
// 这里批准 = 按原请求重放原动作，驳回必须写原因。申请人不能批自己的（后端四眼校验）。
// 阈值是系统参数 approval.compensation.threshold / approval.voucher.threshold，缺省空 = 不需要审批。
import * as React from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PagedTable } from "@/components/ui/paged-table";
import { type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { Toolbar } from "@/components/ui/toolbar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Drawer, Field } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import type { ApprovalKind, ApprovalRequest, ApprovalStatus } from "@/lib/types";

export const APPROVAL_STATUS: StatusMap<ApprovalStatus> = {
  PENDING: { label: "待审批", tone: "warning" },
  APPROVED: { label: "已批准并执行", tone: "success" },
  REJECTED: { label: "已驳回", tone: "outline" },
  FAILED: { label: "批准但执行失败", tone: "danger" },
};
const KIND_LABEL: Record<ApprovalKind, string> = { ORDER_COMPENSATE: "客服补偿", ORDER_WAIVE: "免单", LEDGER_VOUCHER: "手工凭证" };

/** @param kinds 本页关心的种类：订单页看补偿 / 免单，账务页看凭证 */
export function ApprovalsPanel({ kinds, title }: { kinds: ApprovalKind[]; title: string }) {
  const qc = useQueryClient();
  const paging = usePaging();
  const { confirm, dialog } = useConfirm();
  const [status, setStatus] = React.useState<string>("PENDING");
  const [rejecting, setRejecting] = React.useState<ApprovalRequest | null>(null);
  const [note, setNote] = React.useState("");
  const kind = kinds.length === 1 ? kinds[0] : undefined;
  const q = useQuery({
    queryKey: ["approvals", kinds.join(","), status, paging.page, paging.size],
    queryFn: async () => {
      const r = await api.listApprovals({ page: paging.page, size: paging.size, status: status || undefined, kind });
      // 多种类时后端不按种类筛（一个参数只认一个值），在这里收窄 —— 订单页只有补偿与免单两种，量小
      return kind ? r : { ...r, list: r.list.filter((x) => kinds.includes(x.kind)) };
    },
    placeholderData: keepPreviousData,
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["approvals"] });
    qc.invalidateQueries({ queryKey: ["orders"] });
    qc.invalidateQueries({ queryKey: ["fin"] });
  };
  const approve = useMutation({
    mutationFn: (no: string) => api.approveApproval(no),
    onSuccess: (r) => { notify.success(`${r.requestNo} 已批准并执行（${r.resultRef ?? "-"}）`); refresh(); },
    // 执行失败（前提已变）后端已记 FAILED，错误本身由全局弹窗给出；这里只刷新让状态可见
    onError: refresh,
  });
  const reject = useMutation({
    mutationFn: (v: { no: string; note: string }) => api.rejectApproval(v.no, v.note),
    onSuccess: (r) => { notify.success(`${r.requestNo} 已驳回`); setRejecting(null); setNote(""); refresh(); },
  });

  const cols: Column<ApprovalRequest>[] = [
    { header: "申请", cell: (r) => <span className="tabular-nums">{r.requestNo}<span className="block text-muted-foreground">{KIND_LABEL[r.kind]}{r.refNo ? ` · ${r.refNo}` : ""}</span></span> },
    { header: "金额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
    { header: "原因", cell: (r) => <span className="line-clamp-2">{r.reason ?? "-"}</span> },
    { header: "申请人", cell: (r) => <span className="text-muted-foreground">{r.applicantName ?? "-"}<span className="block tabular-nums">{fmtTime(r.createdAt)}</span></span> },
    { header: "状态", cell: (r) => <span><StatusBadge map={APPROVAL_STATUS} value={r.status} />{r.decideNote && <span className="block text-muted-foreground">{r.decideNote}</span>}</span> },
    {
      header: "操作",
      cell: (r) => r.status !== "PENDING" ? <span className="text-muted-foreground">{r.approverName ?? "-"}</span> : (
        <div className="flex items-center gap-1.5">
          <Button size="sm" disabled={approve.isPending} onClick={async () => {
            const ok = await confirm({
              title: `批准 ${KIND_LABEL[r.kind]} ${money(r.amount, r.currency)}`,
              desc: `批准后立即执行原操作${r.refNo ? `（订单 ${r.refNo}）` : ""}。若等待期间前提已变（如订单已关单），执行会失败并记为「批准但执行失败」，不会硬改。`,
              confirmText: "批准并执行",
            });
            if (ok) approve.mutate(r.requestNo);
          }}>批准</Button>
          <Button size="sm" variant="outline" onClick={() => { setRejecting(r); setNote(""); }}>驳回</Button>
        </div>
      ),
    },
  ];

  return (
    <section className="mb-6">
      <h2 className="txt-title mb-3">{title}</h2>
      <Toolbar>
        <FilterSelect value={status} onChange={(v) => { setStatus(v); paging.reset(); }} allLabel="全部状态" options={APPROVAL_STATUS} />
      </Toolbar>
      <PagedTable rowKey={(r: ApprovalRequest) => r.requestNo} columns={cols} query={q} paging={paging}
        empty="没有待审批的申请——审批线（系统参数 approval.*.threshold）留空时，补偿与凭证都即时生效，不会出现在这里" />
      <Drawer open={!!rejecting} onOpenChange={(o) => !o && setRejecting(null)} title={rejecting ? `驳回 ${rejecting.requestNo}` : ""}
        footer={<Button disabled={!note.trim() || reject.isPending} onClick={() => rejecting && reject.mutate({ no: rejecting.requestNo, note: note.trim() })}>确认驳回</Button>}>
        <Field label="驳回原因（必填，申请人会看到）"><Input value={note} maxLength={250} onChange={(e) => setNote(e.target.value)} /></Field>
      </Drawer>
      {dialog}
    </section>
  );
}
