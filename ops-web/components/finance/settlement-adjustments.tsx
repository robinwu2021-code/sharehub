"use client";

// 结算调整项（运营核心流程 C9 / G2）：撤场押金退还、进场费结清、保底补差。
//
// 放在「财务 › 结算单」页签下的一个视图，不开新菜单（菜单变更要走库迁移）。
// 调整项不走提现 —— 它改的是对场地方的应付，经财务确认后并入下一次出账。
//
// 两个动作都有副作用、都要确认（规则 R4）：
//  · 确认：金额可改，**与系统建议值不同必须写说明**（押金退多少写在合同的自由文本里，系统只能给建议）；
//  · 作废：不可逆、原因必填，走 requireText。已并入结算单的不能作废。
import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import {
  canAdjustmentTransition,
  type Adjustment, type AdjustmentKind, type AdjustmentSource, type AdjustmentStatus,
} from "@/lib/types";
import { usePaging } from "@/lib/hooks/use-paging";
import { useCan } from "@/lib/hooks/use-can";
import { money, fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Drawer, Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { SummaryCard } from "@/components/ui/summary-card";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { RefLink } from "@/components/ref-link";
import { ADJUSTMENT_KIND_LABEL } from "./settlement-detail";

export const ADJUSTMENT_STATUS: StatusMap<AdjustmentStatus> = {
  PENDING: { label: "待确认", tone: "warning" },
  CONFIRMED: { label: "已确认待出账", tone: "info" },
  SETTLED: { label: "已并入结算单", tone: "success" },
  VOID: { label: "已作废", tone: "muted" },
};
const ADJUSTMENT_KIND: StatusMap<AdjustmentKind> = {
  DEPOSIT_REFUND: { label: ADJUSTMENT_KIND_LABEL.DEPOSIT_REFUND, tone: "outline" },
  ENTRY_FEE_SETTLE: { label: ADJUSTMENT_KIND_LABEL.ENTRY_FEE_SETTLE, tone: "outline" },
  GUARANTEE_TOPUP: { label: ADJUSTMENT_KIND_LABEL.GUARANTEE_TOPUP, tone: "default" },
};
const ADJUSTMENT_SOURCE: StatusMap<AdjustmentSource> = {
  SITE_CLOSED: { label: "撤场关闭", tone: "outline" },
  GUARANTEE: { label: "保底补差", tone: "outline" },
};

/** 金额输入 → 数字；空串 = 不改（按当前金额确认）。 */
const parseAmount = (v: string) => (v.trim() === "" ? undefined : Number(v));

export function SettlementAdjustments() {
  const qc = useQueryClient();
  const allow = useCan();
  const canConfirm = allow("finance:settlement:confirm");
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const [status, setStatus] = useState("");
  const [kind, setKind] = useState("");
  const [source, setSource] = useState("");
  const [payeeNo, setPayeeNo] = useState("");
  const [confirming, setConfirming] = useState<Adjustment | null>(null);
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [voiding, setVoiding] = useState<Adjustment | null>(null);
  const [reason, setReason] = useState("");

  const q = useQuery({
    queryKey: ["fin-adjustments", paging.page, paging.size, status, kind, source, payeeNo],
    queryFn: () => api.listSettlementAdjustments({
      page: paging.page, size: paging.size,
      status: status || undefined, kind: kind || undefined, source: source || undefined, payeeNo: payeeNo || undefined,
    }),
    // 后端目前只认 status / payeeNo / siteNo：kind / source 照传，并在当页内再筛一遍兜底（后端补上后这一步是空操作）
    select: (d) => (kind || source
      ? { ...d, list: d.list.filter((a) => (!kind || a.kind === kind) && (!source || a.source === source)) }
      : d),
    placeholderData: keepPreviousData,
  });
  // 摘要条（规则 R2）：一张卡 = 一个待办子集，点了就筛
  const pendingQ = useQuery({
    queryKey: ["fin-adjustments", "count", "PENDING"],
    queryFn: () => api.listSettlementAdjustments({ page: 1, size: 1, status: "PENDING" }),
  });
  const confirmedQ = useQuery({
    queryKey: ["fin-adjustments", "count", "CONFIRMED"],
    queryFn: () => api.listSettlementAdjustments({ page: 1, size: 1, status: "CONFIRMED" }),
  });

  const after = () => qc.invalidateQueries({ queryKey: ["fin-adjustments"] });
  const doConfirm = useMutation({
    mutationFn: (v: { adjNo: string; amount?: number; note?: string }) =>
      api.confirmSettlementAdjustment(v.adjNo, { amount: v.amount, note: v.note }),
    onSuccess: (a) => { notify.success(`调整项 ${a.adjNo} 已确认，将并入下一次出账`); setConfirming(null); after(); },
  });
  const doVoid = useMutation({
    mutationFn: (v: { adjNo: string; reason: string }) => api.voidSettlementAdjustment(v.adjNo, v.reason),
    onSuccess: (a) => { notify.success(`调整项 ${a.adjNo} 已作废`); setVoiding(null); after(); },
  });

  const openConfirm = (a: Adjustment) => { setConfirming(a); setAmount(String(a.amount)); setNote(""); };
  const suggested = confirming ? (confirming.suggestedAmount ?? confirming.amount) : 0;
  const amountNum = parseAmount(amount);
  const changed = amountNum != null && !Number.isNaN(amountNum) && Math.round(amountNum * 100) !== Math.round(suggested * 100);

  async function submitConfirm() {
    if (!confirming) return;
    if (amountNum != null && Number.isNaN(amountNum)) { notify.error("金额不是有效数字"); return; }
    if (changed && !note.trim()) { notify.error("确认金额与系统建议值不同，必须写明原因"); return; }
    const final = amountNum ?? confirming.amount;
    const ok = await confirm({
      title: `确认调整项 ${confirming.adjNo}`,
      desc: `按 ${money(final, confirming.currency)} 确认，并入 ${confirming.payeeName ?? confirming.payeeNo} 的下一次出账。确认人与时间将留痕，确认后不能再改金额。`,
      confirmText: "确认",
    });
    if (ok) doConfirm.mutate({ adjNo: confirming.adjNo, amount: amountNum, note: note.trim() || undefined });
  }
  async function submitVoid() {
    if (!voiding) return;
    if (!reason.trim()) { notify.error("作废必须写原因"); return; }
    const ok = await confirm({
      title: `作废调整项 ${voiding.adjNo}`,
      desc: "作废后这笔调整不再并入任何结算单，不可恢复。请输入调整项号确认。",
      danger: true,
      confirmText: "作废",
      requireText: voiding.adjNo,
    });
    if (ok) doVoid.mutate({ adjNo: voiding.adjNo, reason: reason.trim() });
  }

  const cols: Column<Adjustment>[] = [
    { header: "调整项", cell: (a) => <span className="txt-strong tabular-nums">{a.adjNo}</span> },
    {
      header: "场地方",
      cell: (a) => <span>{a.payeeName ?? "-"} <span className="tabular-nums text-muted-foreground">{a.payeeNo}</span></span>,
    },
    { header: "类型", cell: (a) => <StatusBadge map={ADJUSTMENT_KIND} value={a.kind} /> },
    { header: "来源", cell: (a) => <StatusBadge map={ADJUSTMENT_SOURCE} value={a.source} /> },
    {
      header: "站点 / 合同",
      cell: (a) => (
        <div className="space-y-0.5">
          <div><RefLink kind="site" no={a.siteNo} /></div>
          <div className="txt-caption"><RefLink kind="contract" no={a.contractNo} /></div>
        </div>
      ),
    },
    {
      header: "金额",
      className: "text-right",
      cell: (a) => (
        <div>
          <div className="txt-strong tabular-nums">{money(a.amount, a.currency)}</div>
          {a.suggestedAmount != null && a.suggestedAmount !== a.amount && (
            <div className="txt-caption tabular-nums text-muted-foreground">建议 {money(a.suggestedAmount, a.currency)}</div>
          )}
        </div>
      ),
    },
    { header: "状态", cell: (a) => <StatusBadge map={ADJUSTMENT_STATUS} value={a.status} /> },
    { header: "并入结算单", cell: (a) => <RefLink kind="settlement" no={a.settleNo} /> },
    {
      header: "说明",
      cell: (a) => (
        <span className="txt-caption text-muted-foreground" title={a.note ?? undefined}>
          {a.note ? (a.note.length > 24 ? `${a.note.slice(0, 24)}…` : a.note) : "-"}
        </span>
      ),
    },
    {
      header: "确认",
      cell: (a) => <span className="txt-caption text-muted-foreground">{a.confirmedBy ? `${a.confirmedBy} · ${fmtTime(a.confirmedAt)}` : "-"}</span>,
    },
    {
      header: "操作",
      cell: (a) => {
        const canC = canAdjustmentTransition(a.status, "confirm");
        const canV = canAdjustmentTransition(a.status, "void");
        if (!canC && !canV) return <span className="text-muted-foreground">-</span>;
        // 无确认权时按钮禁用 + 说明缺哪个码，不静默隐藏（顶部另有 ReadOnlyNotice）
        const hint = canConfirm ? undefined : "无权限（需要 finance:settlement:confirm）";
        return (
          <div className="flex gap-2">
            {canC && <Button size="sm" disabled={!canConfirm} title={hint} onClick={() => openConfirm(a)}>确认</Button>}
            {canV && <Button size="sm" variant="outline" disabled={!canConfirm} title={hint} onClick={() => { setVoiding(a); setReason(""); }}>作废</Button>}
          </div>
        );
      },
    },
  ];

  const filtered = !!(status || kind || source || payeeNo);
  return (
    <div>
      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <button type="button" className="text-start" onClick={() => { setStatus("PENDING"); paging.reset(); }} aria-label="只看待确认">
          <SummaryCard label="待确认" value={pendingQ.data?.total ?? "—"} sub="撤场生成的建议值，等财务拍板" />
        </button>
        <button type="button" className="text-start" onClick={() => { setStatus("CONFIRMED"); paging.reset(); }} aria-label="只看已确认待出账">
          <SummaryCard label="已确认待出账" value={confirmedQ.data?.total ?? "—"} sub="下一次出账时并入结算单" />
        </button>
      </div>
      {!canConfirm && <ReadOnlyNotice what="调整项确认 / 作废" perm="finance:settlement:confirm" />}
      <Toolbar
        search={payeeNo}
        onSearch={(v) => { setPayeeNo(v.trim()); paging.reset(); }}
        searchPlaceholder="场地方编号（精确，如 VEN300）"
      >
        <FilterSelect value={status} onChange={(v) => { setStatus(v); paging.reset(); }} allLabel="全部状态" options={ADJUSTMENT_STATUS} aria-label="按状态筛选" />
        <FilterSelect value={kind} onChange={(v) => { setKind(v); paging.reset(); }} allLabel="全部类型" options={ADJUSTMENT_KIND} aria-label="按类型筛选" />
        <FilterSelect value={source} onChange={(v) => { setSource(v); paging.reset(); }} allLabel="全部来源" options={ADJUSTMENT_SOURCE} aria-label="按来源筛选" />
      </Toolbar>
      <PagedTable
        query={q}
        paging={paging}
        rowKey={(a) => a.adjNo}
        columns={cols}
        empty={filtered
          ? "这些筛选条件下没有调整项 —— 换个状态 / 类型，或清空场地方编号"
          : "还没有调整项 —— 站点撤场关闭时按合同自动生成押金 / 进场费建议值，保底合同每月出账前自动生成补差"}
        footer={(kind || source) ? (
          <p className="mt-2 txt-caption text-muted-foreground">类型 / 来源筛选在后端未支持前只作用于当前页，总条数可能偏大。</p>
        ) : null}
      />

      {/* 确认：金额可改，改了必须写说明 */}
      <Drawer
        open={!!confirming}
        onOpenChange={(o) => !o && setConfirming(null)}
        title={`确认调整项 ${confirming?.adjNo ?? ""}`}
        desc="负数 = 场地方应返还平台；正数 = 平台补给场地方"
        width="w-[520px]"
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirming(null)}>取消</Button>
            <Button disabled={doConfirm.isPending} onClick={submitConfirm}>确认</Button>
          </>
        }
      >
        {confirming && (
          <div className="space-y-4">
            <Field label="场地方">{confirming.payeeName ?? "-"}（{confirming.payeeNo}）</Field>
            <Field label="类型">{ADJUSTMENT_KIND_LABEL[confirming.kind]} · 合同 <RefLink kind="contract" no={confirming.contractNo} /></Field>
            <Field label="系统建议值"><span className="tabular-nums">{money(suggested, confirming.currency)}</span></Field>
            <Field label="依据">{confirming.note ?? "-"}</Field>
            <div className="space-y-1">
              <label className="txt-caption text-muted-foreground" htmlFor="adj-amount">确认金额（{confirming.currency}）</label>
              <Input id="adj-amount" type="number" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </div>
            <div className="space-y-1">
              <label className="txt-caption text-muted-foreground" htmlFor="adj-note">
                说明{changed ? "（金额与建议值不同，必填）" : "（选填）"}
              </label>
              <Textarea id="adj-note" value={note} onChange={setNote} invalid={changed && !note.trim()}
                placeholder="如：场地方扣除 500 设备安装押金，按实际退还" />
            </div>
          </div>
        )}
      </Drawer>

      {/* 作废：原因必填，提交时 requireText 二次确认 */}
      <Drawer
        open={!!voiding}
        onOpenChange={(o) => !o && setVoiding(null)}
        title={`作废调整项 ${voiding?.adjNo ?? ""}`}
        desc="作废后不再并入任何结算单，不可恢复"
        width="w-[480px]"
        footer={
          <>
            <Button variant="outline" onClick={() => setVoiding(null)}>取消</Button>
            <Button variant="destructive" disabled={doVoid.isPending} onClick={submitVoid}>作废</Button>
          </>
        }
      >
        {voiding && (
          <div className="space-y-4">
            <Field label="金额"><span className="tabular-nums">{money(voiding.amount, voiding.currency)}</span>（{ADJUSTMENT_STATUS[voiding.status].label}）</Field>
            <div className="space-y-1">
              <label className="txt-caption text-muted-foreground" htmlFor="adj-reason">作废原因（必填，追加到说明里留痕）</label>
              <Textarea id="adj-reason" value={reason} onChange={setReason} placeholder="如：合同约定押金不退" />
            </div>
          </div>
        )}
      </Drawer>
      {dialog}
    </div>
  );
}
