"use client";

// 运营管理 › 场站管理 › 预约调价（清单 OM-S4，对标简电「预约调价」）
//
// 提前定好「某个时间把某个方案改成新价格」，到点自动执行；可选到期自动恢复原价。
// 与时段倍率的区别：时段倍率每天重复，这里是一次性改方案本身。
//
// mock 用**惰性执行**模拟定时任务（lib/mock/db/adjust.ts）：每次读列表先把到点的执行掉，
// 以调价单号幂等，服务重启后补执行走同一条路。后端要新表 + 真正的定时任务（TDD BE 待办）。
import { Suspense, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Ban, Eye, Pencil, RotateCcw, Undo2 } from "lucide-react";
import { api } from "@/lib/api";
import type { PriceAdjustment, PriceAdjustPatch, PricePlan } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { money } from "@/lib/utils";
import { ADJUSTABLE, ADJUST_STATUS_LABEL, adjustSummary, simulate, validateAdjustment } from "@/lib/pricing-rules";
import {
  MARKET_TZ, formatMarketTime, formatOffset, tzOffsetMinutes, marketLocalToUtcIso, marketNowLocal,
} from "@/lib/market-time";
import { PageTitle, EmptyState } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { SummaryCard } from "@/components/ui/summary-card";

const zone = formatOffset(tzOffsetMinutes(new Date(), MARKET_TZ));

const STATUS: StatusMap<PriceAdjustment["status"]> = {
  SCHEDULED: { label: "待生效", tone: "outline" },
  APPLIED: { label: "已生效", tone: "success" },
  REVERTED: { label: "已恢复", tone: "muted" },
  CANCELLED: { label: "已撤销", tone: "muted" },
  FAILED: { label: "执行失败", tone: "danger" },
};
const TABS = [
  { key: "pending", label: "待生效", statuses: ["SCHEDULED"] },
  { key: "active", label: "已生效", statuses: ["APPLIED"] },
  { key: "done", label: "已结束", statuses: ["REVERTED", "CANCELLED", "FAILED"] },
  { key: "all", label: "全部", statuses: ["SCHEDULED", "APPLIED", "REVERTED", "CANCELLED", "FAILED"] },
];

type AdjustForm = {
  adjustNo?: string; planNo?: string; name?: string; reason?: string;
  effectiveAt?: string; revertAt?: string;
} & PriceAdjustPatch;

function AdjustmentsInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav } = useI18n();
  const { confirm, dialog } = useConfirm();
  const sp = useSearchParams();
  const canWrite = allow("pricing:adjustment:create");
  const canCancel = allow("pricing:adjustment:cancel");

  const [tab, setTab] = useState("pending");
  const [keyword, setKeyword] = useState("");
  const [planFilter, setPlanFilter] = useState(sp.get("plan") ?? "");
  const [form, setForm] = useState<AdjustForm | null>(null);
  const [editing, setEditing] = useState<PriceAdjustment | undefined>();
  const [detail, setDetail] = useState<PriceAdjustment | null>(null);
  const [cancelTarget, setCancelTarget] = useState<PriceAdjustment | null>(null);
  const [cancelForm, setCancelForm] = useState<Record<string, unknown>>({});

  const listQ = useQuery({
    queryKey: ["op", "adjustments", keyword, planFilter],
    queryFn: () => api.listPriceAdjustments({ page: 1, size: 200, keyword, planNo: planFilter }),
    // 惰性执行：定期回看一次，页面开着也能看到到点生效
    refetchInterval: 60_000,
  });
  const plansQ = useQuery({ queryKey: ["op", "plans-for-adjust"], queryFn: () => api.listPricePlans({ page: 1, size: 200 }) });
  const plans = plansQ.data?.list ?? [];
  const planOf = (no?: string) => plans.find((p) => p.planNo === no);

  const statuses = TABS.find((t) => t.key === tab)!.statuses;
  const rows = (listQ.data?.list ?? []).filter((a) => statuses.includes(a.status));
  const refresh = () => { qc.invalidateQueries({ queryKey: ["op", "adjustments"] }); qc.invalidateQueries({ queryKey: ["op", "plans"] }); };

  const save = useMutation({
    mutationFn: (v: Partial<PriceAdjustment>) => api.savePriceAdjustment(v),
    onSuccess: () => { refresh(); notify.success("已保存，到点自动生效"); setForm(null); },
  });
  const cancel = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.cancelPriceAdjustment(v.no, v.reason),
    onSuccess: () => { refresh(); notify.success("已撤销"); setCancelTarget(null); },
  });
  const revert = useMutation({
    mutationFn: (no: string) => api.revertPriceAdjustment(no),
    onSuccess: () => { refresh(); notify.success("已恢复原价"); },
  });
  const retry = useMutation({
    mutationFn: (no: string) => api.retryPriceAdjustment(no),
    onSuccess: () => { refresh(); notify.success("已重试"); },
  });

  const FIELDS: FieldDef[] = useMemo(() => [
    { key: "name", label: "调价单名称", required: true, maxLength: 64, section: "基本信息", placeholder: "国庆假期上调单价" },
    { key: "planNo", label: "目标方案", type: "select", required: true, readOnlyOnEdit: true, section: "基本信息",
      options: plans.filter((p) => p.status === "ACTIVE" && !p.archivedAt).map((p) => ({ value: p.planNo, label: `${p.name}（${p.planNo}）` })) },
    { key: "reason", label: "调价原因", type: "textarea", rows: 2, required: true, maxLength: 200, section: "基本信息", help: "会记入审计，事后要能说清为什么改价" },
    { key: "effectiveAt", label: `生效时间（${zone}）`, type: "datetime", required: true, section: "时间", help: "至少比现在晚 5 分钟；按市场当地时间填写" },
    { key: "revertAt", label: `恢复时间（${zone}）`, type: "datetime", section: "时间", help: "留空表示不自动恢复；填了就到点还原成调价前的值" },
    ...ADJUSTABLE.map((f) => ({
      key: f.key as string,
      label: `${f.label}${f.unit ? `（${f.unit}）` : ""}`,
      type: "number" as const,
      min: 0,
      section: "调整内容（留空 = 不改这一项）",
    })),
  ], [plans]);

  const openNew = (planNo?: string) => {
    const p = planOf(planNo ?? planFilter) ?? plans.find((x) => x.status === "ACTIVE");
    setEditing(undefined);
    setForm({
      planNo: p?.planNo, name: "", reason: "",
      effectiveAt: marketNowLocal(new Date(Date.now() + 60 * 60_000)),
      revertAt: "",
      // 以方案当前值为初始值：运营只改要改的那几项
      freeMinutes: p?.freeMinutes, unitMinutes: p?.unitMinutes, unitPrice: p?.unitPrice,
      capDaily: p?.capDaily, buyoutPrice: p?.buyoutPrice,
    });
  };
  const openEdit = (a: PriceAdjustment) => {
    const p = planOf(a.planNo);
    setEditing(a);
    setForm({
      adjustNo: a.adjustNo, planNo: a.planNo, name: a.name, reason: a.reason,
      effectiveAt: formatMarketTime(a.effectiveAt).replace(" ", "T"),
      revertAt: a.revertAt ? formatMarketTime(a.revertAt).replace(" ", "T") : "",
      freeMinutes: a.patch.freeMinutes ?? p?.freeMinutes, unitMinutes: a.patch.unitMinutes ?? p?.unitMinutes,
      unitPrice: a.patch.unitPrice ?? p?.unitPrice, capDaily: a.patch.capDaily ?? p?.capDaily,
      buyoutPrice: a.patch.buyoutPrice ?? p?.buyoutPrice,
    });
  };

  /** 表单 → 提交体：只提交与方案现值不同的字段，时间换算成 UTC。 */
  const toPayload = (f: AdjustForm): Partial<PriceAdjustment> => {
    const plan = planOf(f.planNo);
    const patch: PriceAdjustPatch = {};
    for (const a of ADJUSTABLE) {
      const v = f[a.key];
      if (v === undefined || v === null || v === ("" as unknown)) continue;
      const num = Number(v);
      if (plan && num === plan[a.key]) continue;
      patch[a.key] = num;
    }
    return {
      adjustNo: f.adjustNo, planNo: f.planNo, name: f.name, reason: f.reason, patch,
      effectiveAt: f.effectiveAt ? marketLocalToUtcIso(f.effectiveAt) : "",
      revertAt: f.revertAt ? marketLocalToUtcIso(f.revertAt) : null,
    };
  };

  const submit = () => {
    if (!form) return;
    const payload = toPayload(form);
    const errors = validateAdjustment(payload, editing, {
      siblings: (listQ.data?.list ?? []).filter((a) => a.planNo === payload.planNo),
      plan: planOf(payload.planNo), now: new Date(),
    });
    if (errors.length) { notify.error(errors[0]); return; }
    save.mutate(payload);
  };

  const doRevert = async (a: PriceAdjustment) => {
    const ok = await confirm({
      title: `提前恢复「${a.name}」`,
      desc: "立即把方案还原成调价前的值，不等自动恢复时间。已经开始的订单不受影响。",
      confirmText: "恢复原价",
    });
    if (ok) revert.mutate(a.adjustNo);
  };

  // 表单里的即时反馈：改完之后这个方案长什么样、三个典型时长各收多少
  const preview = useMemo(() => {
    if (!form) return null;
    const plan = planOf(form.planNo);
    if (!plan) return null;
    const payload = toPayload(form);
    const next = { ...plan, ...payload.patch } as PricePlan;
    return {
      plan, next, summary: adjustSummary(payload.patch ?? {}, plan),
      rows: [60, 180, 24 * 60].map((m) => ({
        minutes: m,
        before: simulate(plan, m).total,
        after: simulate(next, m).total,
      })),
    };
  }, [form, plans]);

  const cols: Column<PriceAdjustment>[] = [
    { header: "调价单", cell: (a) => (
      <button className="min-w-0 text-left hover:underline" onClick={() => setDetail(a)}>
        <div className="truncate">{a.name}</div>
        <div className="truncate txt-caption text-muted-foreground">{a.adjustNo} · {a.planName}</div>
      </button>
    ) },
    { header: "调整摘要", className: "min-w-56", cell: (a) => (
      <span className="txt-caption">{adjustSummary(a.patch, a.beforeSnapshot ? { ...planOf(a.planNo)!, ...a.beforeSnapshot } : planOf(a.planNo))}</span>
    ) },
    { header: `生效 / 恢复（${zone}）`, className: "whitespace-nowrap", cell: (a) => (
      <span className="txt-caption">
        {formatMarketTime(a.effectiveAt)}
        <br />
        {a.revertAt ? formatMarketTime(a.revertAt) : "不自动恢复"}
      </span>
    ) },
    { header: "状态", className: "whitespace-nowrap", cell: (a) => (
      <div>
        <StatusBadge map={STATUS} value={a.status} />
        {a.failReason && <div className="mt-1 max-w-48 txt-caption text-muted-foreground">{a.failReason}</div>}
      </div>
    ) },
    { header: "创建", className: "whitespace-nowrap", cell: (a) => <span className="txt-caption text-muted-foreground">{a.createdBy}</span> },
    {
      header: "操作",
      cell: (a) => (
        <div className="flex w-max items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => setDetail(a)}><Eye className="size-4" /> 查看</Button>
          {canWrite && a.status === "SCHEDULED" && (
            <Button size="sm" variant="outline" onClick={() => openEdit(a)}><Pencil className="size-4" /> 编辑</Button>
          )}
          {canCancel && a.status === "SCHEDULED" && (
            <Button size="sm" variant="outline" onClick={() => { setCancelTarget(a); setCancelForm({}); }}><Ban className="size-4" /> 撤销</Button>
          )}
          {canWrite && a.status === "APPLIED" && a.revertAt && (
            <Button size="sm" variant="outline" onClick={() => doRevert(a)}><Undo2 className="size-4" /> 提前恢复</Button>
          )}
          {canWrite && a.status === "FAILED" && (
            <Button size="sm" variant="outline" onClick={() => retry.mutate(a.adjustNo)}><RotateCcw className="size-4" /> 重试</Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title={tNav("预约调价")} desc={`到点自动改价、可自动恢复；时间按市场时区（${zone}）`} />
      {!canWrite && <ReadOnlyNotice what="预约调价" perm="pricing:adjustment:create / pricing:adjustment:cancel" note="不能新建、编辑、撤销或恢复" className="mb-3" />}
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="搜索名称 / 单号 / 原因" onAdd={() => openNew()} addLabel="新建调价" canAdd={canWrite}>
        <FilterSelect
          aria-label="目标方案"
          value={planFilter}
          onChange={setPlanFilter}
          options={plans.map((p) => ({ value: p.planNo, label: p.name }))}
          allLabel="全部方案"
        />
      </Toolbar>
      <DataTable
        rowKey={(a: PriceAdjustment) => a.adjustNo}
        columns={cols}
        rows={listQ.isLoading ? undefined : rows}
        loading={listQ.isLoading}
        empty={tab === "pending"
          ? "没有待生效的调价。需要在指定时间改价（如节假日上调、活动期降价）时，在这里新建。"
          : tab === "active" ? "当前没有正在生效的调价。"
          : tab === "done" ? "没有已结束的调价记录。"
          : "还没有任何调价单。"}
      />

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建预约调价"
        titleEdit={`编辑调价单 ${editing?.adjustNo ?? ""}`}
        isEdit={!!editing}
        fields={FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as AdjustForm)}
        onSubmit={submit}
        submitting={save.isPending}
        width="w-[560px]"
      />

      <FormDrawer
        open={!!cancelTarget}
        onOpenChange={(o) => !o && setCancelTarget(null)}
        titleNew=""
        titleEdit={`撤销调价单 ${cancelTarget?.adjustNo ?? ""}`}
        isEdit
        fields={[{ key: "reason", label: "撤销原因", type: "textarea", rows: 2, required: true, maxLength: 100, help: "撤销后这条调价不会执行；原因会记入审计" }]}
        value={cancelForm}
        onChange={setCancelForm}
        onSubmit={() => {
          const reason = String(cancelForm.reason ?? "").trim();
          if (!reason) { notify.error("请填写撤销原因"); return; }
          if (cancelTarget) cancel.mutate({ no: cancelTarget.adjustNo, reason });
        }}
        submitting={cancel.isPending}
      />

      <Drawer open={!!detail} onOpenChange={(o) => !o && setDetail(null)} title={detail?.name ?? ""} desc={detail ? `${detail.adjustNo} · ${detail.planName}` : ""} width="w-[560px]">
        {detail && (() => {
          const plan = planOf(detail.planNo);
          const before = detail.beforeSnapshot ?? (plan ? Object.fromEntries(ADJUSTABLE.filter((f) => detail.patch[f.key] != null).map((f) => [f.key, plan[f.key]])) : {});
          return (
            <div>
              <div className="grid grid-cols-2 gap-3">
                <SummaryCard label="状态" value={ADJUST_STATUS_LABEL[detail.status]} sub={detail.failReason ?? undefined} />
                <SummaryCard label={`生效时间（${zone}）`} value={formatMarketTime(detail.effectiveAt)} sub={detail.revertAt ? `恢复 ${formatMarketTime(detail.revertAt)}` : "不自动恢复"} />
              </div>
              <h3 className="mb-2 mt-4 txt-strong">变更对比</h3>
              <table className="w-full txt-body">
                <thead>
                  <tr className="txt-caption text-muted-foreground">
                    <th className="py-1 text-start">字段</th><th className="py-1 text-end">调价前</th><th className="py-1 text-end">调价后</th>
                  </tr>
                </thead>
                <tbody>
                  {ADJUSTABLE.filter((f) => detail.patch[f.key] != null).map((f) => {
                    const b = (before as PriceAdjustPatch)[f.key];
                    const a = detail.patch[f.key]!;
                    const fmt = (v?: number) => v == null ? "—" : f.money ? money(v, plan?.currency) : `${v}${f.unit ?? ""}`;
                    return (
                      <tr key={f.key}>
                        <td className="py-1.5">{f.label}</td>
                        <td className="py-1.5 text-end tabular-nums text-muted-foreground">{fmt(b)}</td>
                        <td className="py-1.5 text-end tabular-nums text-primary">{fmt(a)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <h3 className="mb-2 mt-4 txt-strong">执行记录</h3>
              <ul className="txt-caption text-muted-foreground">
                <li>创建：{formatMarketTime(detail.createdAt)} · {detail.createdBy}</li>
                <li>计划生效：{formatMarketTime(detail.effectiveAt)}{detail.appliedAt ? ` → 实际 ${formatMarketTime(detail.appliedAt)}` : "（未执行）"}</li>
                {detail.revertAt && <li>计划恢复：{formatMarketTime(detail.revertAt)}{detail.revertedAt ? ` → 实际 ${formatMarketTime(detail.revertedAt)}` : "（未执行）"}</li>}
                {detail.failReason && <li>说明：{detail.failReason}</li>}
              </ul>
              <h3 className="mb-2 mt-4 txt-strong">调价原因</h3>
              <p className="txt-body">{detail.reason}</p>
            </div>
          );
        })()}
      </Drawer>

      {/* 表单打开时的即时影响预览 */}
      {form && preview && (
        <div className="pointer-events-none fixed bottom-4 start-1/2 z-[var(--z-toast)] w-[min(92vw,520px)] -translate-x-1/2 rounded-card bg-card p-3 shadow-pop">
          <div className="mb-1 txt-caption text-muted-foreground">改动预览：{preview.summary}</div>
          <div className="flex flex-wrap gap-3 txt-caption">
            {preview.rows.map((r) => (
              <span key={r.minutes}>
                {r.minutes < 60 ? `${r.minutes} 分钟` : r.minutes < 24 * 60 ? `${r.minutes / 60} 小时` : "1 天"}：
                <span className="text-muted-foreground line-through">{money(r.before, preview.plan.currency)}</span>
                <span className="ms-1 text-primary">{money(r.after, preview.plan.currency)}</span>
              </span>
            ))}
          </div>
        </div>
      )}
      {dialog}
    </div>
  );
}

export default function FeeAdjustmentsPage() {
  return <Suspense fallback={null}><AdjustmentsInner /></Suspense>;
}
