"use client";

// 结算单详情抽屉（`/finance?tab=settlements&no=STL…`）：对账单 · 构成明细 · 打印。
//
// 对账单是给**场地方**看的（裁决 #2：首版不做场地方门户，线下发对账单），
// 所以它必须能回答「这笔钱怎么算出来的」：订单 → 按合同 × 比例分成 → 调整项 → 本期应付。
// 打印版是后端渲染的整页 HTML，要带登录令牌，不能用普通链接打开 —— 取回后开 blob 窗口。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import {
  canSettlementTransition,
  type AdjustmentKind, type SettlementStatus, type ShareRecord, type StatementAdjustLine, type StatementLang, type StatementShareLine,
} from "@/lib/types";
import { useAuth } from "@/lib/auth";
import { money, fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { SummaryCard } from "@/components/ui/summary-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Tabs } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/misc";
import { StateActions } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";

// 结算单状态：全站同色（待确认=warning / 已确认=default / 已打款=success）。页面与详情共用这一份。
export const SETTLEMENT_STATUS: StatusMap<SettlementStatus> = {
  GEN: { label: "待确认", tone: "warning" },
  CONFIRMED: { label: "已确认", tone: "default" },
  PAID: { label: "已打款", tone: "success" },
};
export const ADJUSTMENT_KIND_LABEL: Record<AdjustmentKind, string> = {
  DEPOSIT_REFUND: "押金退还",
  ENTRY_FEE_SETTLE: "进场费结清",
  GUARANTEE_TOPUP: "保底补差",
};
const PAYEE_TYPE_LABEL = { VENUE: "场地方", AGENT: "代理商" } as const;
const STEPS = [
  { key: "GEN", label: "待确认" },
  { key: "CONFIRMED", label: "已确认" },
  { key: "PAID", label: "已打款" },
];
const LANGS: { value: StatementLang; label: string }[] = [
  { value: "zh", label: "中文" },
  { value: "en", label: "English" },
  { value: "ar", label: "العربية" },
];

/**
 * 分成行的「来源号」：场地方是合同号（可跳合同详情），代理商实测是分润规则号（SR…）——
 * 后端同一个字段 contractNo 装两种号，按合同去链接代理那一行会跳到一个不存在的合同。
 */
function shareCols(currency: string, payeeType: string): Column<StatementShareLine>[] {
  return [
    {
      header: payeeType === "VENUE" ? "合同" : "分润规则",
      cell: (r) => (payeeType === "VENUE" ? <RefLink kind="contract" no={r.contractNo} /> : <span className="tabular-nums">{r.contractNo ?? "-"}</span>),
    },
    { header: "分成比例", className: "text-right", cell: (r) => <span className="tabular-nums">{(r.rate * 100).toFixed(1)}%</span> },
    { header: "订单数", className: "text-right", cell: (r) => <span className="tabular-nums">{r.orders}</span> },
    { header: "订单收入", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.gross, currency)}</span> },
    { header: "分成", className: "text-right", cell: (r) => <span className="txt-strong tabular-nums">{money(r.amount, currency)}</span> },
  ];
}
function adjustCols(currency: string): Column<StatementAdjustLine>[] {
  return [
    { header: "调整项", cell: (r) => <span className="tabular-nums">{r.adjNo}</span> },
    { header: "类型", cell: (r) => ADJUSTMENT_KIND_LABEL[r.kind] ?? r.kind },
    { header: "合同", cell: (r) => <RefLink kind="contract" no={r.contractNo} /> },
    { header: "账期", cell: (r) => <span className="tabular-nums">{r.period ?? "-"}</span> },
    { header: "金额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.amount, currency)}</span> },
    { header: "说明", cell: (r) => <span className="txt-caption text-muted-foreground">{r.note ?? "-"}</span> },
  ];
}
const RECORD_COLS: Column<ShareRecord>[] = [
  { header: "明细号", cell: (r) => <span className="tabular-nums">{r.recordNo}</span> },
  { header: "订单", cell: (r) => <RefLink kind="order" no={r.orderNo} /> },
  { header: "交易额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.grossAmount, r.currency)}</span> },
  { header: "比例", className: "text-right", cell: (r) => <span className="tabular-nums">{(r.rate * 100).toFixed(0)}%</span> },
  { header: "分润", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.amount, r.currency)}</span> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
];

/**
 * 打开可打印对账单。**先同步开窗再取数**：在 await 之后才 window.open 会被弹窗拦截
 * （浏览器只放行用户手势同一调用栈里的开窗）。
 */
async function openPrintable(settleNo: string, lang: StatementLang) {
  const w = window.open("", "_blank");
  if (!w) { notify.error("浏览器拦截了新窗口，请允许本站弹窗后重试"); return; }
  try {
    const html = await api.getSettlementStatementHtml(settleNo, lang);
    const url = URL.createObjectURL(new Blob([html], { type: "text/html;charset=utf-8" }));
    w.location.href = url;
    // 新窗口载入后再回收；立即回收会让它读到一个已失效的地址
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    w.close();
    notify.error(e instanceof Error ? e.message : "对账单生成失败");
  }
}

export function SettlementDetailDrawer({ settleNo, onClose }: { settleNo: string | null; onClose: () => void }) {
  const qc = useQueryClient();
  const username = useAuth((s) => s.username);
  const [tab, setTab] = useState<"statement" | "records">("statement");
  const [lang, setLang] = useState<StatementLang>("zh");
  const [printing, setPrinting] = useState(false);

  const viewQ = useQuery({
    queryKey: ["stl-view", settleNo],
    queryFn: () => api.getSettlement(settleNo!),
    enabled: !!settleNo,
    retry: false,
  });
  const stmtQ = useQuery({
    queryKey: ["stl-statement", settleNo, viewQ.data?.settlement.status],
    queryFn: () => api.getSettlementStatement(settleNo!),
    enabled: !!settleNo && !!viewQ.data && tab === "statement",
  });
  const recordsQ = useQuery({
    queryKey: ["stl-records", settleNo],
    queryFn: () => api.listSettlementRecords(settleNo!, { page: 1, size: UNPAGED_SIZE }),
    enabled: !!settleNo && tab === "records",
  });
  const confirmStl = useMutation({
    mutationFn: (no: string) => api.confirmSettlement(no, username || undefined),
    onSuccess: (s) => {
      notify.success(`结算单 ${s.settleNo} 已确认`);
      for (const k of [["fin"], ["stl-view"], ["stl-statement"]]) qc.invalidateQueries({ queryKey: k });
    },
  });

  const s = viewQ.data?.settlement;
  const st = stmtQ.data;
  return (
    <Drawer
      open={!!settleNo}
      onOpenChange={(o) => !o && onClose()}
      title="结算单详情"
      desc="对账单 = 订单 → 按合同 × 比例分成 → 调整项 → 本期应付；确认后金额锁定"
      width="w-[820px]"
    >
      {viewQ.error ? (
        <ErrorState error={viewQ.error} onRetry={() => viewQ.refetch()} />
      ) : !s ? (
        <div className="txt-caption text-muted-foreground">加载中…</div>
      ) : (
        <div className="space-y-5">
          <DetailHeader
            no={s.settleNo}
            title={s.payeeName}
            badge={<StatusBadge map={SETTLEMENT_STATUS} value={s.status} />}
            meta={`${PAYEE_TYPE_LABEL[s.payeeType] ?? s.payeeType} ${s.payeeNo} · 账期 ${s.period} · 生成于 ${fmtTime(s.createdAt)}`
              + (s.confirmedBy ? ` · ${s.confirmedBy} 确认于 ${fmtTime(s.confirmedAt)}` : "")}
            stepper={<StatusStepper steps={STEPS} current={s.status} />}
            actions={
              <StateActions actions={[{
                key: "confirm", label: "确认结算", primary: true, perm: "finance:settlement:confirm",
                when: canSettlementTransition(s.status, "confirm"),
                confirm: {
                  title: `确认结算 ${s.settleNo}`,
                  desc: `确认后 ${s.payeeName}（${s.period}）的 ${money(s.totalAmount, s.currency)} 进入应付，金额锁定不可再改，确认人与时间将留痕。`,
                  confirmText: "确认结算",
                },
                onRun: () => confirmStl.mutateAsync(s.settleNo),
              }]} />
            }
          />

          <Tabs
            tabs={[{ key: "statement", label: "对账单" }, { key: "records", label: `构成明细（${viewQ.data!.details.length}）` }]}
            value={tab}
            onChange={(k) => setTab(k as "statement" | "records")}
          />

          {tab === "statement" && (
            stmtQ.error ? <ErrorState error={stmtQ.error} onRetry={() => stmtQ.refetch()} />
            : !st ? <div className="txt-caption text-muted-foreground">对账单生成中…</div>
            : (
              <div className="space-y-5">
                <div className="flex flex-wrap items-center justify-end gap-2">
                  <span className="txt-caption text-muted-foreground">打印语言</span>
                  <FilterSelect value={lang} onChange={(v) => setLang(v as StatementLang)} options={LANGS} aria-label="对账单语言" />
                  <Button
                    variant="outline"
                    disabled={printing}
                    onClick={async () => { setPrinting(true); await openPrintable(s.settleNo, lang); setPrinting(false); }}
                  >打印对账单</Button>
                </div>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <SummaryCard label="订单数" value={st.orderCount} sub={`订单收入 ${money(st.grossTotal, st.currency)}`} />
                  <SummaryCard label="分成合计" value={money(st.shareTotal, st.currency)} />
                  <SummaryCard label="调整项合计" value={money(st.adjustTotal, st.currency)} sub={st.adjustments.length ? `${st.adjustments.length} 项` : "本期无调整"} />
                  <SummaryCard label="本期应付" value={money(st.total, st.currency)} sub="分成 + 调整项" />
                </div>
                <section className="space-y-2">
                  <div className="txt-strong">{st.payeeType === "VENUE" ? "分成（按合同 × 比例）" : "分润（按规则 × 比例）"}</div>
                  <DataTable
                    rowKey={(r) => `${r.contractNo ?? "-"}-${r.rate}`}
                    columns={shareCols(st.currency, st.payeeType)}
                    rows={st.shares}
                    error={stmtQ.error}
                    onRetry={stmtQ.refetch}
                    empty="本期没有分成 —— 该对象本账期没有产生订单，应付只来自下方的调整项"
                  />
                </section>
                <section className="space-y-2">
                  <div className="txt-strong">调整项</div>
                  <DataTable
                    rowKey={(r) => r.adjNo}
                    columns={adjustCols(st.currency)}
                    rows={st.adjustments}
                    error={stmtQ.error}
                    onRetry={stmtQ.refetch}
                    empty="本期没有并入调整项 —— 撤场押金、进场费结清、保底补差经财务确认后，在下一次出账时并入"
                  />
                  <p className="txt-caption text-muted-foreground">负数 = 场地方应返还平台（押金、进场费折算）；正数 = 平台补给场地方（保底补差）。</p>
                </section>
              </div>
            )
          )}

          {tab === "records" && (
            <div className="space-y-2">
              <Field label="结算金额">{money(s.totalAmount, s.currency)}（= 下列分润明细之和）</Field>
              <DataTable
                rowKey={(r) => r.recordNo}
                columns={RECORD_COLS}
                rows={recordsQ.data?.list}
                loading={recordsQ.isLoading}
                error={recordsQ.error}
                onRetry={recordsQ.refetch}
                empty="该账期没有分润明细 —— 理论上不该出现（无明细不允许出单），若看到请核对分润规则"
              />
            </div>
          )}
        </div>
      )}
    </Drawer>
  );
}
