"use client";

// 运营管理 › 场站管理 › 收费方案（清单 OM-S3，对标简电「收费方案」）
//
// 与旧入口（计费定价 › 计费模板）读写同一份数据，旧页面不动。
// 规则见 lib/pricing-rules（摘要、试算、校验），表单与 mock 共用。
//
// **第一期按现有的单计费项模型**（免费时长 / 计费单位 / 单价 / 日封顶 / 买断价）。
// 后端库里另有计费项、阶梯价两张子表，多计费项与阶梯价要等后端接口对齐后再做（TDD §2）。
//
// 时段倍率（原「活动/时段价」菜单）并入本页，作为方案之外的一层乘数。
import { Suspense, useMemo, useState } from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CalendarClock, Calculator, Copy, Crosshair, Pencil, Power } from "lucide-react";
import Link from "next/link";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import type { PricePlan, PricingSchedule } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { money } from "@/lib/utils";
import { featureReady, pageReady } from "@/lib/backend-ready";
import { planSummary, simulate, validatePricePlan } from "@/lib/rules/pricing-rules";
import { PageTitle, EmptyState } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { PlanScopeDrawer } from "@/components/operation/plan-scope-drawer";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { SummaryCard } from "@/components/ui/summary-card";

const PLAN_STATUS: StatusMap<PricePlan["status"]> = {
  ACTIVE: { label: "启用", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};

/**
 * @form POST /api/trade/price-plans
 * @form POST /api/trade/price-plans/{planNo}
 */
const PLAN_FIELDS: FieldDef[] = [
  { key: "name", label: "方案名称", required: true, maxLength: 64, section: "基本信息", placeholder: "机场高价" },
  // `scope` 是一句**给人看的描述**，不参与取价。真正决定「这个方案对谁生效」的是
  // 「适用范围」（price_plan_scope，见行末的按钮）—— 2026-09-23 之前这里写什么都不影响金额，
  // 而 help 文案还让人去用另一个菜单，两处都不生效（ADR-028 / V49）。
  { key: "scope", label: "描述", maxLength: 64, section: "基本信息", placeholder: "机场专用 / 默认",
    help: "只是一句说明，方便在列表里认出它。真正决定对谁生效的是「适用范围」，在列表行上维护" },
  { key: "currency", label: "币种", type: "select", required: true, section: "基本信息",
    options: ["AED", "SAR", "QAR", "EGP"].map((c) => ({ value: c, label: c })), readOnlyOnEdit: true,
    help: "保存后不能改——已用这个方案下过的单都是这个币种" },
  { key: "freeMinutes", label: "免费时长（分钟）", type: "number", required: true, min: 0, section: "计费规则", help: "从借出开始算，这段时间内归还不收费" },
  { key: "unitMinutes", label: "计费单位（分钟）", type: "number", required: true, min: 1, section: "计费规则", help: "不足一个单位按一个算" },
  { key: "unitPrice", label: "单价", type: "number", required: true, min: 0, section: "计费规则", help: "每个计费单位收多少" },
  { key: "capDaily", label: "日封顶", type: "number", required: true, min: 0, section: "计费规则", help: "每 24 小时最多收这么多；0 = 不封顶" },
  { key: "buyoutPrice", label: "买断价", type: "number", required: true, min: 0, section: "计费规则", help: "累计到这个金额就不再计费，设备归用户；须 ≥ 日封顶" },
];

/*
 * 时段用**结构化字段**填，不再让人写一句自由文本。
 *
 * 2026-09-23 之前只存一个展示串（`周六-周日 18:00-22:00`），后端要判倍率就得复刻
 * 前端那个按中文标签解析的 parser —— 而界面还有英文与阿语。用展示串做判断，
 * 与本项目栽过的「按名字连表」是同一类错（ADR-028 / V49）。
 * `period` 仍然落库，但降级为纯展示，由下面这几个字段拼出来。
 */
const WEEKDAYS = [
  { value: "1", label: "周一" }, { value: "2", label: "周二" }, { value: "3", label: "周三" },
  { value: "4", label: "周四" }, { value: "5", label: "周五" }, { value: "6", label: "周六" },
  { value: "7", label: "周日" },
];
const HHMM = "^([01][0-9]|2[0-3]):[0-5][0-9]$";
/**
 * @form POST /api/trade/pricing-schedules
 * @form POST /api/trade/pricing-schedules/{scheduleNo}
 */
const SCHEDULE_FIELDS: FieldDef[] = [
  { key: "name", label: "名称", required: true, maxLength: 40, placeholder: "晚高峰", section: "基本信息" },
  { key: "days", label: "星期", type: "multiselect", csv: true, options: WEEKDAYS, section: "时段",
    placeholder: "留空 = 每天" },
  { key: "timeFrom", label: "开始时刻", section: "时段", placeholder: "18:00",
    pattern: { re: HHMM, msg: "格式为 HH:mm（24 时制）" },
    help: "两端都留空 = 全天；跨零点（22:00-06:00）合法。只填一端视为没配完，不会生效" },
  { key: "timeTo", label: "结束时刻", section: "时段", placeholder: "23:00",
    pattern: { re: HHMM, msg: "格式为 HH:mm（24 时制）" } },
  { key: "expr", label: "节假日表达式", section: "时段", maxLength: 60, placeholder: "公共假日 / 斋月全月",
    help: "星期+时刻表达不了的日历事件写这里。⚠️ 后端本期不参与计算，只原样保留" },
  { key: "multiplier", label: "倍率", type: "number", required: true, min: 0.1, max: 5, section: "倍率",
    help: "1.5 = 上浮 50%，0.8 = 打八折。作用在计费段上，不影响免费时长与封顶。多条命中取最大，不相乘" },
  { key: "active", label: "启用", type: "switch", section: "倍率" },
];

/** 结构化字段 → 展示串。落库与列表看到的是同一份，避免「看到的」与「存下的」两套。 */
function periodText(v: Partial<PricingSchedule>): string {
  const days = (v.days ?? "").split(",").map((x) => x.trim()).filter(Boolean)
    .map((n) => WEEKDAYS.find((w) => w.value === n)?.label ?? n);
  const time = v.timeFrom && v.timeTo ? `${v.timeFrom}-${v.timeTo}` : "";
  const expr = (v.expr ?? "").trim();
  return [days.length && days.length < 7 ? days.join("、") : "每天", time, expr]
    .filter(Boolean).join(" ");
}

function FeePlansInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { tNav } = useI18n();
  const { confirm, dialog } = useConfirm();
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const canWrite = allow("pricing:plan:create") || allow("pricing:plan:update");
  const canSchedule = allow("pricing:rule:update");
  const canToggle = featureReady("fee-plans.status");

  const tab = sp.get("tab") === "periods" ? "periods" : "plans";
  const setTab = (t: string) => {
    const q = new URLSearchParams(sp.toString());
    if (t === "plans") q.delete("tab"); else q.set("tab", t);
    router.replace(q.size ? `${pathname}?${q.toString()}` : pathname, { scroll: false });
  };

  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Partial<PricePlan> | null>(null);
  const [editing, setEditing] = useState<PricePlan | undefined>();
  const [schedForm, setSchedForm] = useState<Partial<PricingSchedule> | null>(null);
  const [editingSched, setEditingSched] = useState<PricingSchedule | undefined>();
  const [simPlan, setSimPlan] = useState<PricePlan | null>(null);
  const [scopePlan, setScopePlan] = useState<PricePlan | null>(null);
  const [simMinutes, setSimMinutes] = useState(90);
  const [simMultiplier, setSimMultiplier] = useState(1);

  const plansQ = useQuery({
    queryKey: ["op", "plans", keyword, status, showArchived],
    queryFn: () => api.listPricePlans({ page: 1, size: UNPAGED_SIZE, keyword, showArchived }),
  });
  const schedQ = useQuery({
    queryKey: ["op", "periods"],
    queryFn: () => api.listPricingSchedules({ page: 1, size: UNPAGED_SIZE }),
    enabled: tab === "periods",
  });
  // 预约调价接口后端未实现：真实后端模式下不发这个请求，「待生效调价」列显示为未开放
  const adjustmentsReady = pageReady("fee-adjustments");
  const adjQ = useQuery({
    queryKey: ["op", "plan-adjustments"],
    queryFn: () => api.listPriceAdjustments({ page: 1, size: UNPAGED_SIZE, status: "SCHEDULED" }),
    enabled: adjustmentsReady,
  });

  const plans = useMemo(
    () => (plansQ.data?.list ?? []).filter((p) => !status || p.status === status),
    [plansQ.data, status],
  );
  const pendingByPlan = useMemo(() => {
    const m = new Map<string, number>();
    for (const a of adjQ.data?.list ?? []) m.set(a.planNo, (m.get(a.planNo) ?? 0) + 1);
    return m;
  }, [adjQ.data]);

  const refresh = () => { qc.invalidateQueries({ queryKey: ["op", "plans"] }); qc.invalidateQueries({ queryKey: ["op", "periods"] }); };

  const savePlan = useMutation({
    mutationFn: (v: Partial<PricePlan>) => api.savePricePlan(v as Partial<PricePlan> & { planNo?: string }),
    onSuccess: () => { refresh(); notify.success("已保存"); setForm(null); },
  });
  const saveSched = useMutation({
    // period 由结构化字段派生后一并提交 —— 它只是展示串，不让人手写也不让两边各存一套
    mutationFn: (v: Partial<PricingSchedule>) => api.savePricingSchedule(
      { ...v, period: periodText(v) } as Partial<PricingSchedule> & { ruleNo?: string }),
    onSuccess: () => { refresh(); notify.success("已保存"); setSchedForm(null); },
  });
  const archive = useMutation({ mutationFn: (no: string) => api.archivePricePlan(no), onSuccess: () => { refresh(); notify.success("已归档"); } });
  const unarchive = useMutation({ mutationFn: (no: string) => api.unarchivePricePlan(no), onSuccess: () => { refresh(); notify.success("已恢复"); } });

  const openNew = () => { setEditing(undefined); setForm({ currency: "AED", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 60, scope: "默认", status: "ACTIVE" }); };
  const openEdit = (p: PricePlan) => { setEditing(p); setForm({ ...p }); };
  const openCopy = (p: PricePlan) => {
    setEditing(undefined);
    setForm({ ...p, planNo: undefined, name: `${p.name}（副本）`, archivedAt: null });
  };

  const submitPlan = async () => {
    if (!form) return;
    const v = {
      ...form,
      freeMinutes: Number(form.freeMinutes), unitMinutes: Number(form.unitMinutes),
      unitPrice: Number(form.unitPrice), capDaily: Number(form.capDaily), buyoutPrice: Number(form.buyoutPrice),
    };
    const errors = validatePricePlan(v, editing, plansQ.data?.list ?? []);
    if (errors.length) { notify.error(errors[0]); return; }
    if (editing && editing.status === "ACTIVE") {
      const pending = adjustmentsReady ? (pendingByPlan.get(editing.planNo) ?? 0) : 0;
      const ok = await confirm({
        title: `保存方案「${editing.name}」`,
        desc: `修改会立即对之后的新订单生效；已经开始的订单按下单时的方案计费。${pending ? `\n注意：这个方案还有 ${pending} 条待生效的预约调价，到点会覆盖你现在改的字段。` : ""}\n如果想在指定时间生效，请改用「预约调价」。`,
        confirmText: "保存",
      });
      if (!ok) return;
    }
    savePlan.mutate(v);
  };

  const toggleStatus = async (p: PricePlan) => {
    const disabling = p.status === "ACTIVE";
    const ok = await confirm({
      title: `${disabling ? "停用" : "启用"}方案「${p.name}」`,
      desc: disabling
        ? "停用后，原本命中这个方案的站点会回落到默认方案。已经开始的订单不受影响。"
        : "启用后，命中这个方案的站点将按它计费。",
      danger: disabling,
      confirmText: disabling ? "停用" : "启用",
    });
    if (ok) savePlan.mutate({ ...p, status: disabling ? "DISABLED" : "ACTIVE" });
  };

  const planCols: Column<PricePlan>[] = [
    { header: "方案", cell: (p) => (
      <div className="min-w-0">
        <div className="truncate">{p.name}</div>
        <div className="truncate txt-caption text-muted-foreground">
          {p.planNo}{p.scope ? ` · ${p.scope}` : ""}
        </div>
      </div>
    ) },
    { header: "计费摘要", className: "min-w-64", cell: (p) => <span className="txt-caption">{planSummary(p)}</span> },
    { header: "待生效调价", className: "whitespace-nowrap", cell: (p) => {
      if (!adjustmentsReady) return <span className="txt-caption text-muted-foreground">未开放</span>;
      const n = pendingByPlan.get(p.planNo) ?? 0;
      return n ? <Link href="/operation/fee-adjustments" className="txt-caption text-primary hover:underline">{n} 条</Link> : <span className="text-muted-foreground">—</span>;
    } },
    { header: "状态", className: "whitespace-nowrap", cell: (p) => <StatusBadge map={PLAN_STATUS} value={p.status} /> },
    {
      header: "操作",
      cell: (p) => (
        <div className="flex w-max items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => setScopePlan(p)}>
            <Crosshair className="size-4" /> 适用范围
          </Button>
          <Button size="sm" variant="outline" onClick={() => { setSimPlan(p); setSimMinutes(90); setSimMultiplier(1); }}>
            <Calculator className="size-4" /> 试算
          </Button>
          <ArchiveActions
            archived={!!p.archivedAt}
            canWrite={canWrite}
            onArchive={async () => { if (await confirm(archiveConfirm("收费方案", p.name))) archive.mutate(p.planNo); }}
            onUnarchive={async () => { if (await confirm(unarchiveConfirm("收费方案", p.name))) unarchive.mutate(p.planNo); }}
            actions={
              <>
                <Button size="sm" variant="outline" onClick={() => openEdit(p)}><Pencil className="size-4" /> 编辑</Button>
                <Button size="sm" variant="outline" onClick={() => openCopy(p)}><Copy className="size-4" /> 复制</Button>
                {adjustmentsReady && (
                  <Button size="sm" variant="outline" onClick={() => router.push(`/operation/fee-adjustments?plan=${p.planNo}`)}>
                    <CalendarClock className="size-4" /> 预约调价
                  </Button>
                )}
                {canToggle && <Button size="sm" variant="outline" onClick={() => toggleStatus(p)}><Power className="size-4" /> {p.status === "ACTIVE" ? "停用" : "启用"}</Button>}
              </>
            }
          />
        </div>
      ),
    },
  ];

  const schedCols: Column<PricingSchedule>[] = [
    { header: "名称", cell: (s) => s.name },
    { header: "时段", cell: (s) => <span className="txt-caption">{s.period}</span> },
    { header: "倍率", className: "whitespace-nowrap text-right", cell: (s) => <span className="tabular-nums">× {s.multiplier}</span> },
    { header: "状态", className: "whitespace-nowrap", cell: (s) => <StatusBadge map={{ on: { label: "启用", tone: "success" }, off: { label: "停用", tone: "muted" } }} value={s.active ? "on" : "off"} /> },
    ...(canSchedule ? [{
      header: "操作", className: "whitespace-nowrap",
      cell: (s: PricingSchedule) => (
        <Button size="sm" variant="outline" onClick={() => { setEditingSched(s); setSchedForm({ ...s }); }}><Pencil className="size-4" /> 编辑</Button>
      ),
    }] : []),
  ];

  const sim = simPlan ? simulate(simPlan, simMinutes, simMultiplier) : null;

  return (
    <div>
      <PageTitle title={tNav("收费方案")} desc="计费规则与时段倍率；改动立即对新订单生效，定时改价请用预约调价" />
      {!canWrite && <ReadOnlyNotice what="收费方案维护" perm="pricing:plan:create / pricing:plan:update" note="不能新增、编辑、停用或归档" className="mb-3" />}
      <Tabs tabs={[{ key: "plans", label: "收费方案" }, { key: "periods", label: "时段倍率" }]} value={tab} onChange={setTab} />

      {tab === "plans" && (
        <>
          <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder="搜索方案名 / 编号" onAdd={openNew} addLabel="新增方案" canAdd={canWrite}>
            <FilterSelect aria-label="状态" value={status} onChange={setStatus} options={PLAN_STATUS} allLabel="全部状态" />
            <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
          </Toolbar>
          <DataTable
            rowKey={(p: PricePlan) => p.planNo}
            columns={planCols}
            rows={plansQ.isLoading ? undefined : plans}
            loading={plansQ.isLoading}
            error={plansQ.error}
            onRetry={plansQ.refetch}
            rowClassName={archivedRowClass}
            empty={keyword || status ? "没有符合条件的方案。" : "还没有收费方案。没有方案时所有站点都按系统兜底价计费，建议先建一个默认方案。"}
          />
        </>
      )}

      {tab === "periods" && (
        <>
          <Notice>
            时段倍率只作用在计费段上（免费时长与封顶不受影响）：例如晚高峰 ×1.5，原本每 30 分钟 AED 3 的方案，
            在这个时段按 AED 4.5 计费。多条同时命中时的取值规则以取价引擎为准，配置前请与后端确认。
          </Notice>
          <Toolbar
            onAdd={() => { setEditingSched(undefined); setSchedForm({ multiplier: 1.5, active: true }); }}
            addLabel="新增时段倍率"
            canAdd={canSchedule}
          />
          <DataTable
            rowKey={(s: PricingSchedule) => s.ruleNo}
            columns={schedCols}
            rows={schedQ.isLoading ? undefined : schedQ.data?.list}
            loading={schedQ.isLoading}
            error={schedQ.error}
            onRetry={schedQ.refetch}
            empty="还没有时段倍率。不配的话全天同一个价，节假日与高峰期无法区分。"
          />
        </>
      )}

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增收费方案"
        titleEdit={`编辑方案 ${editing?.planNo ?? ""}`}
        isEdit={!!editing}
        fields={PLAN_FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<PricePlan>)}
        onSubmit={submitPlan}
        submitting={savePlan.isPending}
        width="w-[520px]"
      />
      <FormDrawer
        open={!!schedForm}
        onOpenChange={(o) => !o && setSchedForm(null)}
        titleNew="新增时段倍率"
        titleEdit={`编辑时段倍率 ${editingSched?.ruleNo ?? ""}`}
        isEdit={!!editingSched}
        fields={SCHEDULE_FIELDS}
        value={(schedForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setSchedForm(v as Partial<PricingSchedule>)}
        onSubmit={() => schedForm && saveSched.mutate({ ...schedForm, multiplier: Number(schedForm.multiplier) })}
        submitting={saveSched.isPending}
      />

      <Drawer
        open={!!simPlan}
        onOpenChange={(o) => !o && setSimPlan(null)}
        title={`试算 · ${simPlan?.name ?? ""}`}
        desc="用与真实计费同一份规则计算，可逐段核对"
        width="w-[520px]"
      >
        {simPlan && sim && (
          <div>
            <p className="mb-3 txt-caption text-muted-foreground">{planSummary(simPlan)}</p>
            <div className="mb-4 flex items-end gap-3">
              <label className="flex-1">
                <div className="mb-1 txt-caption text-muted-foreground">租借时长（分钟）</div>
                <Input type="number" min={0} value={simMinutes} onChange={(e) => setSimMinutes(Math.max(0, Number(e.target.value)))} />
              </label>
              <label className="w-32">
                <div className="mb-1 txt-caption text-muted-foreground">时段倍率</div>
                <Input type="number" min={0.1} step={0.1} value={simMultiplier} onChange={(e) => setSimMultiplier(Number(e.target.value) || 1)} />
              </label>
            </div>
            <div className="mb-3 flex flex-wrap gap-2">
              {[30, 90, 240, 24 * 60, 72 * 60].map((m) => (
                <button key={m} onClick={() => setSimMinutes(m)} className="rounded-control bg-secondary px-3 py-1 txt-caption hover:bg-accent">
                  {m < 60 ? `${m} 分钟` : m < 24 * 60 ? `${m / 60} 小时` : `${m / (24 * 60)} 天`}
                </button>
              ))}
            </div>
            <SummaryCard label="应收合计" value={money(sim.total, sim.currency)} sub={sim.buyout ? "已达买断价，设备归用户" : undefined} />
            <table className="mt-3 w-full txt-body">
              <tbody>
                {sim.segments.map((seg, i) => (
                  <tr key={i} className="align-top">
                    <td className="py-1.5 pe-3 whitespace-nowrap">{seg.label}</td>
                    <td className="py-1.5 pe-3 txt-caption text-muted-foreground">{seg.detail}</td>
                    <td className="py-1.5 text-right tabular-nums whitespace-nowrap">{money(seg.amount, sim.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!simPlan && <EmptyState title="没有选择方案" />}
      </Drawer>
      <PlanScopeDrawer plan={scopePlan} onClose={() => setScopePlan(null)} canWrite={canWrite} />
      {dialog}
    </div>
  );
}

export default function FeePlansPage() {
  return <Suspense fallback={null}><FeePlansInner /></Suspense>;
}
