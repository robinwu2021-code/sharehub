"use client";

// 付款任务（V158，商家一期 B4；需求方案 §八）—— 平台向商家付款，财务手动执行，一般三个月一次。
//
//   选付款截止日 → 预览 → 生成 → 复核（另一位同事）→ 导出转账文件 → 银行线下转账 → 确认到账（逐笔可标失败）
//
// 放在「结算 › 付款 › 付款任务」页签（原「提现审核」，V160 更名）的运营视图最上面：一期提现就是平台主动付款（D8），商家不申请。
// 下面原有的提现单表照常显示 —— 付款任务生成的每一笔也是一张提现单，在那里同样查得到。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { Drawer, Field } from "@/components/ui/drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import { PAGE_SIZE } from "@/lib/constants";
import type {
  PayoutBlockedReason, PayoutPreviewItem, PayoutTask, PayoutTaskItem, PayoutTaskStatus, WithdrawalStatus,
} from "@/lib/types";

export const PAYOUT_TASK_STATUS: StatusMap<PayoutTaskStatus> = {
  PENDING_REVIEW: { label: "待复核", tone: "warning" },
  PENDING_TRANSFER: { label: "待转账", tone: "info" },
  DONE: { label: "已完成", tone: "success" },
  CANCELLED: { label: "已撤回", tone: "muted" },
};

const ITEM_STATUS: StatusMap<WithdrawalStatus> = {
  APPLY: { label: "待复核", tone: "warning" },
  AUDIT: { label: "待复核", tone: "warning" },
  PAYING: { label: "待转账", tone: "info" },
  PAID: { label: "已到账", tone: "success" },
  FAILED: { label: "失败 / 撤回", tone: "danger" },
};

/** 「本次不能付款」原因：服务端只给码，文案在这里（阿语/英语界面也要看得懂）。 */
const BLOCKED: StatusMap<PayoutBlockedReason> = {
  NO_ACCOUNT: { label: "未录收款账户", tone: "danger" },
  NO_FULL_ACCOUNT: { label: "收款账户缺完整账号，需补录", tone: "danger" },
  SUSPENDED: { label: "商家已停用", tone: "danger" },
  MIXED_CURRENCY: { label: "结算单币种不一致", tone: "danger" },
};

/** 默认截止日：上个月最后一天 —— 「三个月一次」通常在月初做，付到上月底。 */
function lastMonthEnd(): string {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 0)).toISOString().slice(0, 10);
}

export function PayoutTasks() {
  const allow = useCan();
  const qc = useQueryClient();
  const canCreate = allow("finance:withdrawal:create");
  const canAudit = allow("finance:withdrawal:audit");
  const canExport = allow("finance:withdrawal:export");
  const canPay = allow("finance:withdrawal:pay");

  const [creating, setCreating] = useState(false);
  const [cutoff, setCutoff] = useState(lastMonthEnd());
  const [openNo, setOpenNo] = useState<string | null>(null);

  const listQ = useQuery({ queryKey: ["fin", "payout-tasks"], queryFn: () => api.listPayoutTasks({ page: 1, size: PAGE_SIZE }) });
  const previewQ = useQuery({
    queryKey: ["fin", "payout-preview", cutoff],
    queryFn: () => api.previewPayoutTask(cutoff),
    enabled: creating && /^\d{4}-\d{2}-\d{2}$/.test(cutoff),
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["fin", "payout-tasks"] });
    qc.invalidateQueries({ queryKey: ["fin", "payout-task"] });
    qc.invalidateQueries({ queryKey: ["fin", "withdrawals"] });
  };
  const create = useMutation({
    mutationFn: () => api.createPayoutTask(cutoff),
    onSuccess: (t) => { refresh(); setCreating(false); setOpenNo(t.taskNo); notify.success(`付款任务 ${t.taskNo} 已生成，等待另一位同事复核`); },
    onError: (e: Error) => notify.error(e.message),
  });

  const taskCols: Column<PayoutTask>[] = [
    { header: "任务号", cell: (t) => (
      <button type="button" className="txt-strong tabular-nums underline-offset-4 hover:underline" onClick={() => setOpenNo(t.taskNo)}>{t.taskNo}</button>
    ) },
    { header: "付款截止日", cell: (t) => <span className="tabular-nums">{t.cutoffDate}</span> },
    { header: "笔数", className: "text-right", cell: (t) => <span className="tabular-nums">{t.itemCount}</span> },
    { header: "合计", className: "text-right", cell: (t) => <span className="tabular-nums">{money(t.totalAmount, t.currency)}</span> },
    { header: "状态", cell: (t) => <StatusBadge map={PAYOUT_TASK_STATUS} value={t.status} /> },
    { header: "发起", cell: (t) => <span className="text-muted-foreground">{t.createdName ?? "-"}</span> },
    { header: "复核", cell: (t) => <span className="text-muted-foreground">{t.reviewedName ?? "-"}</span> },
    { header: "操作", cell: (t) => <Button size="sm" variant="outline" onClick={() => setOpenNo(t.taskNo)}>详情</Button> },
  ];

  const previewCols: Column<PayoutPreviewItem>[] = [
    { header: "商家", cell: (i) => <span><span className="tabular-nums">{i.payeeNo}</span> · {i.payeeName}</span> },
    { header: "账期", cell: (i) => <span className="tabular-nums text-muted-foreground">{i.periods.join("、")}</span> },
    { header: "金额", className: "text-right", cell: (i) => <span className="tabular-nums">{money(i.amount, i.currency)}</span> },
    { header: "收款账户", cell: (i) => <span className="tabular-nums text-muted-foreground">{i.accountMasked ?? "-"}</span> },
    { header: "本次", cell: (i) => (i.blockedReason ? <StatusBadge map={BLOCKED} value={i.blockedReason} /> : <span className="txt-body">纳入</span>) },
  ];

  const p = previewQ.data;
  return (
    <section className="mb-6 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="txt-title">付款任务</div>
          <p className="txt-body text-muted-foreground">按结算单向商家付款：选截止日生成，另一位同事复核，导出转账文件到网银转账，转完回来确认到账。</p>
        </div>
        {canCreate && <Button onClick={() => setCreating(true)}>生成付款任务</Button>}
      </div>
      {!canCreate && !canAudit && !canPay && <ReadOnlyNotice what="付款任务" perm={["finance:withdrawal:create", ":audit", ":pay"]} />}
      <DataTable
        rowKey={(t: PayoutTask) => t.taskNo}
        columns={taskCols}
        rows={listQ.data?.list}
        loading={listQ.isLoading} error={listQ.error} onRetry={listQ.refetch}
        empty="还没有付款任务——结算单确认后，选一个付款截止日生成；一般三个月做一次"
      />

      <Drawer
        open={creating}
        onOpenChange={(o) => !o && setCreating(false)}
        title="生成付款任务"
        desc="账期结束日不晚于截止日、已确认、尚未付款的商家结算单纳入本次；每个商家合并为一笔"
        width="w-[720px]"
        footer={
          <>
            <Button variant="outline" onClick={() => setCreating(false)}>取消</Button>
            <Button disabled={!p || p.payableCount === 0 || create.isPending} onClick={() => create.mutate()}>
              生成（{p?.payableCount ?? 0} 笔{p ? ` · ${money(p.payableTotal, p.currency)}` : ""}）
            </Button>
          </>
        }
      >
        <Field label="付款截止日"><DateInput value={cutoff} onChange={(e) => setCutoff(e.target.value)} /></Field>
        {p && p.items.some((i) => i.blockedReason) && (
          <Notice>标红的商家本次不能付款，结算单留在可付款范围，处理完原因后下次任务会自动带上。</Notice>
        )}
        <DataTable
          rowKey={(i: PayoutPreviewItem) => i.payeeNo}
          columns={previewCols}
          rows={p?.items}
          loading={previewQ.isLoading} error={previewQ.error} onRetry={previewQ.refetch}
          empty="截止日之前没有待付款的结算单——结算单要先确认，或把截止日往后挪"
        />
      </Drawer>

      <PayoutTaskDrawer taskNo={openNo} onClose={() => setOpenNo(null)} onChanged={refresh}
        canAudit={canAudit} canExport={canExport} canPay={canPay} />
    </section>
  );
}

function PayoutTaskDrawer({ taskNo, onClose, onChanged, canAudit, canExport, canPay }: {
  taskNo: string | null; onClose: () => void; onChanged: () => void;
  canAudit: boolean; canExport: boolean; canPay: boolean;
}) {
  const q = useQuery({ queryKey: ["fin", "payout-task", taskNo], queryFn: () => api.getPayoutTask(taskNo!), enabled: !!taskNo });
  const [rejectReason, setRejectReason] = useState("");
  const [batchRef, setBatchRef] = useState("");
  const [transferDate, setTransferDate] = useState(new Date().toISOString().slice(0, 10));
  /** 标为失败的笔：withdrawNo → 原因。没标的按到账处理。 */
  const [failed, setFailed] = useState<Record<string, string>>({});
  const t = q.data;

  const done = (msg: string) => { onChanged(); notify.success(msg); };
  const audit = useMutation({
    mutationFn: (approve: boolean) => api.auditPayoutTask(taskNo!, approve, approve ? undefined : rejectReason),
    onSuccess: (r) => done(r.status === "CANCELLED" ? "已驳回，结算单已放回可付款范围" : "复核通过，可以导出转账文件"),
    onError: (e: Error) => notify.error(e.message),
  });
  const exportFile = useMutation({
    mutationFn: () => api.exportPayoutTask(taskNo!),
    onSuccess: (csv) => {
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
      const a = document.createElement("a");
      a.href = url; a.download = `payout-${taskNo}.csv`; a.click();
      URL.revokeObjectURL(url);
      done("转账文件已导出（含完整账号，请妥善保管，转账后删除本地文件）");
    },
    onError: (e: Error) => notify.error(e.message),
  });
  const confirm = useMutation({
    mutationFn: () => {
      const paying = (t?.items ?? []).filter((i) => i.status === "PAYING");
      return api.confirmPayoutTask(taskNo!, {
        bankBatchRef: batchRef, transferDate,
        results: paying.map((i) => (i.withdrawNo in failed
          ? { withdrawNo: i.withdrawNo, success: false, failReason: failed[i.withdrawNo] }
          : { withdrawNo: i.withdrawNo, success: true })),
      });
    },
    onSuccess: () => { setFailed({}); done("已确认到账；失败的笔已放回可付款范围"); },
    onError: (e: Error) => notify.error(e.message),
  });

  const transferring = t?.status === "PENDING_TRANSFER";
  const itemCols: Column<PayoutTaskItem>[] = [
    { header: "序号", className: "tabular-nums", cell: (i) => i.seq },
    { header: "商家", cell: (i) => <span><span className="tabular-nums">{i.payeeNo}</span> · {i.payeeName}</span> },
    { header: "金额", className: "text-right", cell: (i) => <span className="tabular-nums">{money(i.amount, i.currency)}</span> },
    { header: "收款账户", cell: (i) => <span className="text-muted-foreground">{i.accountName ?? "-"} <span className="tabular-nums">{i.accountMasked ?? ""}</span></span> },
    { header: "状态", cell: (i) => <StatusBadge map={ITEM_STATUS} value={i.status} /> },
    { header: transferring && canPay ? "失败原因（到账则留空）" : "参考号 / 原因", cell: (i) => (
      transferring && canPay && i.status === "PAYING" ? (
        <Input value={failed[i.withdrawNo] ?? ""} placeholder="银行退回时填写"
          onChange={(e) => setFailed((m) => {
            const next = { ...m };
            if (e.target.value) next[i.withdrawNo] = e.target.value; else delete next[i.withdrawNo];
            return next;
          })} />
      ) : <span className="tabular-nums text-muted-foreground">{i.payRef ?? i.failReason ?? "-"}</span>
    ) },
  ];

  return (
    <Drawer
      open={!!taskNo}
      onOpenChange={(o) => !o && onClose()}
      title={`付款任务 ${taskNo ?? ""}`}
      desc={t ? `截止 ${t.cutoffDate} · ${t.itemCount} 笔 · ${money(t.totalAmount, t.currency)} · 导出 ${t.exportCount} 次` : undefined}
      width="w-[880px]"
      footer={t && (
        <div className="flex flex-wrap justify-end gap-2">
          {t.status === "PENDING_REVIEW" && canAudit && (
            <>
              <Button variant="outline" disabled={audit.isPending} onClick={() => audit.mutate(false)}>驳回</Button>
              <Button disabled={audit.isPending} onClick={() => audit.mutate(true)}>复核通过</Button>
            </>
          )}
          {transferring && canExport && (
            <Button variant="outline" disabled={exportFile.isPending} onClick={() => exportFile.mutate()}>导出转账文件</Button>
          )}
          {transferring && canPay && (
            <Button disabled={!batchRef.trim() || !transferDate || confirm.isPending} onClick={() => confirm.mutate()}>
              确认到账{Object.keys(failed).length ? `（${Object.keys(failed).length} 笔失败）` : ""}
            </Button>
          )}
        </div>
      )}
    >
      {t?.status === "PENDING_REVIEW" && (
        <>
          <Notice>复核人不能是发起人（{t.createdName ?? "-"}）。驳回会撤回整个任务，结算单全部放回可付款范围。</Notice>
          {canAudit && <Field label="驳回原因（驳回时必填）"><Input value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} /></Field>}
        </>
      )}
      {transferring && canPay && (
        <>
          <Notice>在网银完成批量转账后回到这里确认。被银行退回的笔在下表填写原因，其余按到账处理；失败的笔会放回可付款范围，下次任务自动带上。</Notice>
          <Field label="银行批次参考号"><Input value={batchRef} onChange={(e) => setBatchRef(e.target.value)} placeholder="网银批量转账的批次号" /></Field>
          <Field label="转账日期"><DateInput value={transferDate} onChange={(e) => setTransferDate(e.target.value)} /></Field>
        </>
      )}
      {t?.status === "CANCELLED" && t.rejectReason && <Notice>已撤回：{t.rejectReason}</Notice>}
      {t?.status === "DONE" && (
        <Notice>已完成 · 银行批次 {t.bankBatchRef ?? "-"} · 转账日期 {t.transferDate ?? "-"} · 确认于 {t.confirmedAt ? fmtTime(t.confirmedAt) : "-"}</Notice>
      )}
      <DataTable
        rowKey={(i: PayoutTaskItem) => i.withdrawNo}
        columns={itemCols}
        rows={t?.items}
        loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="这个任务里没有提现单"
      />
    </Drawer>
  );
}
