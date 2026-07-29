"use client";

// 工单闭环（G6）：开单 → 派单 → 接单 → 处理 → 完成 → 验收关单，全程留痕。
// 状态机定义在 lib/types/workorder.ts（WO_TRANSITIONS），页面按钮与 mock/后端校验共用同一份；
// 页面只负责「不给点非法动作」，真正的拒绝在服务端（mock 层抛 WorkOrderTransitionError）。
import { Suspense, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { Input, Select } from "@/components/ui/input";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { WoStatusBadge, WO_TYPE_LABEL } from "@/components/status";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { nextActions } from "@/lib/types";
import type {
  WorkOrder, WorkOrderAction, WorkOrderDraft, WoAuditResult, SlaRule, InspectionPlan,
} from "@/lib/types";

const SIZE = 10;
const PRIO: Record<string, [string, "danger" | "warning" | "muted" | "outline"]> = {
  URGENT: ["紧急", "danger"], HIGH: ["高", "danger"], MEDIUM: ["中", "warning"], LOW: ["低", "muted"],
};
const STAFF = ["Ali", "Omar", "Sara", "Wang"];
const BOARD_COLS: { key: string; label: string }[] = [
  { key: "CREATED", label: "待派单" }, { key: "DISPATCHED", label: "已派单" },
  { key: "PROCESSING", label: "处理中" }, { key: "DONE", label: "已完成" }, { key: "CLOSED", label: "已关闭" },
];
const TABS = [
  { key: "list", label: "列表" }, { key: "board", label: "看板" },
  { key: "sla", label: "SLA 管理", phase: 2 as const }, { key: "inspection", label: "巡检计划", phase: 2 as const },
];
type View = "list" | "board" | "sla" | "inspection";

const ACTION_LABEL: Record<WorkOrderAction, string> = {
  dispatch: "派单", accept: "接单", process: "提交处理", complete: "完成", close: "验收关单", reject: "驳回", rework: "退回返工",
};
const SOURCE_LABEL: Record<WorkOrder["source"], string> = {
  ALERT: "告警转入", USER: "投诉转入", VENUE: "场地方报障", MANUAL: "手工开单",
};
const AUDIT_LABEL: Record<WoAuditResult, string> = { PASS: "验收合格", PASS_WITH_ISSUE: "有条件通过（有遗留）", FAIL: "验收不合格（退回返工）" };

const WO_TYPE_OPTIONS = Object.entries(WO_TYPE_LABEL).map(([value, label]) => ({ value, label }));
const PRIO_OPTIONS = [
  { value: "LOW", label: "低" }, { value: "MEDIUM", label: "中" },
  { value: "HIGH", label: "高" }, { value: "URGENT", label: "紧急" },
];

const SLA_FIELDS: FieldDef[] = [
  { key: "slaNo", label: "SLA 编号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "woType", label: "工单类型", type: "select", options: WO_TYPE_OPTIONS },
  { key: "responseMins", label: "响应时限（分钟）", type: "number" },
  { key: "resolveMins", label: "解决时限（分钟）", type: "number" },
  { key: "escalateTo", label: "升级至", placeholder: "运维主管" },
  { key: "active", label: "启用", type: "switch" },
];
const INSPECTION_FIELDS: FieldDef[] = [
  { key: "planNo", label: "计划编号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "route", label: "巡检路线", placeholder: "市中心 A 线" },
  { key: "frequency", label: "频率", type: "select", options: [
    { value: "每日", label: "每日" }, { value: "每周", label: "每周" }, { value: "双周", label: "双周" }, { value: "每月", label: "每月" },
  ] },
  { key: "nextAt", label: "下次巡检（ISO 时间）", placeholder: "2026-07-20T09:00:00Z" },
  { key: "assignee", label: "负责人", placeholder: "Ali" },
  { key: "active", label: "启用", type: "switch" },
];

const NEW_WO: Partial<WorkOrderDraft> = {
  type: "FAULT", priority: "MEDIUM", cabinetNo: "", locationName: "", description: "", expectedAt: "",
};

function WorkOrdersInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const sp = useSearchParams();
  const qView = sp.get("view");
  const [view, setView] = useState<View>(TABS.some((t) => t.key === qView) ? (qView as View) : "list");
  useEffect(() => { if (qView && TABS.some((t) => t.key === qView)) setView(qView as View); }, [qView]);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");

  // —— 抽屉状态：开单 / 派单 / 处理·完成 / 验收关单 / 驳回 / 详情 ——
  const [woForm, setWoForm] = useState<Partial<WorkOrderDraft> | null>(null);
  const [dispatch, setDispatch] = useState<WorkOrder | null>(null);
  const [assignee, setAssignee] = useState(STAFF[0]);
  const [handle, setHandle] = useState<{ wo: WorkOrder; action: "process" | "complete" } | null>(null);
  const [handleNote, setHandleNote] = useState("");
  const [parts, setParts] = useState("");
  const [closing, setClosing] = useState<WorkOrder | null>(null);
  const [auditResult, setAuditResult] = useState<WoAuditResult>("PASS");
  const [auditNote, setAuditNote] = useState("");
  const [rejecting, setRejecting] = useState<WorkOrder | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  // 同一个「填原因」抽屉服务两个动作：driver 驳回退回待派单 / rework 验收不合格退回返工
  const [rejectMode, setRejectMode] = useState<"reject" | "rework">("reject");
  const [detail, setDetail] = useState<WorkOrder | null>(null);

  const [slaKw, setSlaKw] = useState("");
  const [inspKw, setInspKw] = useState("");
  const [slaForm, setSlaForm] = useState<Partial<SlaRule> | null>(null);
  const [inspForm, setInspForm] = useState<Partial<InspectionPlan> | null>(null);

  // 列表分页；看板一次取较多再按状态分列
  const list = useQuery({
    queryKey: ["workorders", page, keyword, status, type],
    queryFn: () => api.listWorkOrders({ page, size: SIZE, keyword, status: status || undefined, type: type || undefined }),
    placeholderData: keepPreviousData, enabled: view === "list",
  });
  const board = useQuery({
    queryKey: ["workorders-board", keyword, type],
    queryFn: () => api.listWorkOrders({ page: 1, size: 200, keyword, type: type || undefined }),
    enabled: view === "board",
  });
  const sla = useQuery({
    queryKey: ["sla-rules", page, slaKw],
    queryFn: () => api.listSlaRules({ page, size: SIZE, keyword: slaKw }),
    placeholderData: keepPreviousData, enabled: view === "sla",
  });
  const inspection = useQuery({
    queryKey: ["inspection-plans", page, inspKw],
    queryFn: () => api.listInspectionPlans({ page, size: SIZE, keyword: inspKw }),
    placeholderData: keepPreviousData, enabled: view === "inspection",
  });
  // 开单时机柜下拉：与设备台账同一份数据，避免手打错柜号
  const cabinetOpts = useQuery({
    queryKey: ["wo-cabinet-options"],
    queryFn: () => api.listCabinets({ page: 1, size: 200 }),
    enabled: !!woForm,
  });

  const refreshWo = () => {
    qc.invalidateQueries({ queryKey: ["workorders"] });
    qc.invalidateQueries({ queryKey: ["workorders-board"] });
  };
  /** 流转成功统一收口：提示 + 刷新列表与看板 + 关抽屉；失败走全局 MutationCache.onError（含非法迁移报错）。 */
  const ok = (msg: string, after?: () => void) => () => { notify.success(msg); refreshWo(); after?.(); };

  const doCreate = useMutation({
    mutationFn: (v: WorkOrderDraft) => api.createWorkOrder(v),
    onSuccess: ok("工单已创建", () => setWoForm(null)),
  });
  const doDispatch = useMutation({
    mutationFn: (v: { no: string; assignee: string }) => api.dispatchWorkOrder(v.no, v.assignee),
    onSuccess: ok("已派单", () => setDispatch(null)),
  });
  const doAccept = useMutation({
    mutationFn: (no: string) => api.acceptWorkOrder(no),
    onSuccess: ok("已接单，工单进入处理中"),
  });
  const doHandle = useMutation({
    mutationFn: (v: { no: string; action: "process" | "complete"; handleNote: string; partsReplaced?: string }) =>
      v.action === "complete"
        ? api.completeWorkOrder(v.no, { handleNote: v.handleNote, partsReplaced: v.partsReplaced })
        : api.processWorkOrder(v.no, { handleNote: v.handleNote, partsReplaced: v.partsReplaced }),
    onSuccess: ok("处理结果已提交", () => setHandle(null)),
  });
  const doClose = useMutation({
    mutationFn: (v: { no: string; auditResult: WoAuditResult; auditNote: string }) =>
      api.closeWorkOrder(v.no, { auditResult: v.auditResult, auditNote: v.auditNote }),
    onSuccess: ok("验收通过，工单已关闭", () => setClosing(null)),
  });
  const doReject = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.rejectWorkOrder(v.no, v.reason),
    onSuccess: ok("已驳回，工单退回待派单", () => setRejecting(null)),
  });
  // 验收不合格退回返工：与 reject 复用同一个「填原因」抽屉，靠 rejecting.mode 区分
  const doRework = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.reworkWorkOrder(v.no, v.reason),
    onSuccess: ok("已退回返工，工单回到处理中", () => setRejecting(null)),
  });

  const canCreate = allow("workorder:wo:create");
  const canDispatch = allow("workorder:wo:dispatch");
  const canHandle = allow("workorder:wo:handle");
  const canClose = allow("workorder:wo:close");
  const canSla = allow("workorder:sla:update");
  const canInspection = allow("workorder:inspection:update");
  const canAny = canCreate || canDispatch || canHandle || canClose;

  /** 动作 → 所需权限码（列表/看板/详情三处共用，避免各写一套） */
  const permOf = (a: WorkOrderAction) =>
    a === "dispatch" || a === "reject" ? canDispatch : a === "close" || a === "rework" ? canClose : canHandle;

  const openAction = (w: WorkOrder, a: WorkOrderAction) => {
    if (a === "dispatch") { setDispatch(w); setAssignee(w.assigneeName ?? STAFF[0]); return; }
    if (a === "accept") { doAccept.mutate(w.woNo); return; }
    if (a === "process" || a === "complete") { setHandle({ wo: w, action: a }); setHandleNote(""); setParts(w.partsReplaced ?? ""); return; }
    if (a === "close") { setClosing(w); setAuditResult("PASS"); setAuditNote(""); return; }
    setRejectMode(a === "rework" ? "rework" : "reject");
    setRejecting(w); setRejectReason("");
  };

  /** 当前状态下有权执行的动作。列表操作列只出前两个，其余进详情抽屉（操作列 >2 收「更多」）。 */
  const actionsOf = (w: WorkOrder) => nextActions(w.status).filter(permOf);

  const busy = doDispatch.isPending || doAccept.isPending || doHandle.isPending || doClose.isPending || doReject.isPending || doRework.isPending;

  const ActionButtons = ({ w, max }: { w: WorkOrder; max?: number }) => {
    const acts = actionsOf(w);
    const shown = max ? acts.slice(0, max) : acts;
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        {shown.map((a) => (
          <Button
            key={a} size="sm" disabled={busy}
            variant={a === "reject" || a === "rework" ? "outline" : "default"}
            onClick={() => openAction(w, a)}
          >{ACTION_LABEL[a]}</Button>
        ))}
        {acts.length === 0 && <span className="text-muted-foreground">-</span>}
      </div>
    );
  };

  const WO_FIELDS: FieldDef[] = useMemo(() => {
    const cabs = cabinetOpts.data?.list ?? [];
    return [
      { key: "type", label: "工单类型", type: "select", options: WO_TYPE_OPTIONS, required: true, section: "基本信息" },
      {
        key: "cabinetNo", label: "机柜号", type: "select", required: true, section: "基本信息",
        options: [{ value: "", label: "请选择机柜" }, ...cabs.map((c) => ({ value: c.cabinetNo, label: `${c.cabinetNo} · ${c.locationName}` }))],
        help: cabinetOpts.isLoading ? "机柜列表加载中…" : "与设备台账同一份数据",
      },
      { key: "locationName", label: "站点", maxLength: 40, section: "基本信息", placeholder: "留空则按机柜自动带出", help: "仅在机柜刚移机、台账未同步时才需手填" },
      { key: "priority", label: "优先级", type: "select", options: PRIO_OPTIONS, required: true, section: "基本信息" },
      { key: "description", label: "问题描述", type: "textarea", rows: 4, required: true, maxLength: 200, section: "问题与时限", placeholder: "现象、影响面、已做过的排查" },
      { key: "expectedAt", label: "期望完成时间", type: "date", section: "问题与时限", help: "超期工单会在看板与 SLA 统计里标红" },
    ];
  }, [cabinetOpts.data, cabinetOpts.isLoading]);

  const saveSla = useMutation({
    mutationFn: (v: Partial<SlaRule>) => api.saveSlaRule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["sla-rules"] }); notify.success(t("common.success")); setSlaForm(null); },
  });
  const saveInspection = useMutation({
    mutationFn: (v: Partial<InspectionPlan>) => api.saveInspectionPlan(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["inspection-plans"] }); notify.success(t("common.success")); setInspForm(null); },
  });

  const cols: Column<WorkOrder>[] = [
    { header: "工单号", cell: (w) => <span className="font-medium tabular-nums">{w.woNo}</span> },
    { header: "类型", cell: (w) => WO_TYPE_LABEL[w.type] },
    { header: "来源", cell: (w) => <span className="text-muted-foreground">{SOURCE_LABEL[w.source]}{w.sourceNo ? ` · ${w.sourceNo}` : ""}</span> },
    { header: "柜机", cell: (w) => w.cabinetNo },
    { header: "点位", cell: (w) => <span className="text-muted-foreground">{w.locationName}</span> },
    { header: "优先级", cell: (w) => <Badge tone={PRIO[w.priority][1]}>{PRIO[w.priority][0]}</Badge> },
    { header: "状态", cell: (w) => <WoStatusBadge s={w.status} /> },
    { header: "处理人", cell: (w) => <span className="text-muted-foreground">{w.handlerName ?? w.assigneeName ?? "未派单"}</span> },
    { header: "期望完成", cell: (w) => <span className="text-muted-foreground">{w.expectedAt ? fmtTime(w.expectedAt) : "-"}</span> },
    { header: "创建", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.createdAt)}</span> },
    {
      header: t("common.actions"),
      cell: (w) => (
        <div className="flex flex-wrap items-center gap-1.5">
          <ActionButtons w={w} max={2} />
          <Button size="sm" variant="outline" onClick={() => setDetail(w)}>详情</Button>
        </div>
      ),
    },
  ];

  const slaCols: Column<SlaRule>[] = [
    { header: "SLA 编号", cell: (s) => <span className="font-medium">{s.slaNo}</span> },
    { header: "工单类型", cell: (s) => <Badge tone="outline">{s.woType}</Badge> },
    { header: "响应时限", cell: (s) => <span className="tabular-nums">{s.responseMins} 分钟</span> },
    { header: "解决时限", cell: (s) => <span className="tabular-nums">{s.resolveMins} 分钟</span> },
    { header: "升级至", cell: (s) => <span className="text-muted-foreground">{s.escalateTo}</span> },
    { header: "状态", cell: (s) => <Badge tone={s.active ? "success" : "muted"}>{s.active ? "启用" : "停用"}</Badge> },
    { header: t("common.actions"), cell: (s) => canSla ? <Button size="sm" variant="outline" onClick={() => setSlaForm(s)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const inspectionCols: Column<InspectionPlan>[] = [
    { header: "计划编号", cell: (p) => <span className="font-medium">{p.planNo}</span> },
    { header: "巡检路线", cell: (p) => p.route },
    { header: "频率", cell: (p) => <Badge tone="outline">{p.frequency}</Badge> },
    { header: "下次巡检", cell: (p) => <span className="text-muted-foreground">{fmtTime(p.nextAt)}</span> },
    { header: "负责人", cell: (p) => <span className="text-muted-foreground">{p.assignee}</span> },
    { header: "状态", cell: (p) => <Badge tone={p.active ? "success" : "muted"}>{p.active ? "启用" : "停用"}</Badge> },
    { header: t("common.actions"), cell: (p) => canInspection ? <Button size="sm" variant="outline" onClick={() => setInspForm(p)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const rows = board.data?.list ?? [];

  return (
    <div>
      <TabHeader tabs={TABS} value={view} onChange={(k) => { setView(k as View); setPage(1); }} />

      {(view === "list" || view === "board") && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索工单号 / 柜机 / 点位 / 来源单号 / 处理人"
            onAdd={canCreate ? () => setWoForm({ ...NEW_WO }) : undefined}
            addLabel="新建工单"
          >
            <Select value={type} onChange={(e) => { setType(e.target.value); setPage(1); }}>
              <option value="">全部类型</option>
              {Object.entries(WO_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
            {view === "list" && (
              <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
                <option value="">全部状态</option>
                {BOARD_COLS.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
              </Select>
            )}
          </Toolbar>
          {!canAny && (
            <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
              仅可查看：当前角色无工单开单/流转权限（workorder:wo:create / :dispatch / :handle / :close）
            </div>
          )}
        </>
      )}

      {view === "sla" && (
        <>
          <Toolbar
            search={slaKw}
            onSearch={(v) => { setSlaKw(v); setPage(1); }}
            searchPlaceholder="搜索 SLA 编号 / 类型 / 升级对象"
            onAdd={canSla ? () => setSlaForm({ woType: "FAULT", responseMins: 30, resolveMins: 240, escalateTo: "", active: true }) : undefined}
            addLabel="新增 SLA"
          />
          <DataTable rowKey={(s: SlaRule) => s.slaNo} columns={slaCols} rows={sla.data?.list} loading={sla.isLoading}
            empty="暂无 SLA 规则——尚未配置响应/解决时限，工单不会触发超时升级；点右上「新增 SLA」建一条。" />
          {sla.data && <Pagination page={page} size={SIZE} total={sla.data.total} onPage={setPage} />}
        </>
      )}

      {view === "inspection" && (
        <>
          <Toolbar
            search={inspKw}
            onSearch={(v) => { setInspKw(v); setPage(1); }}
            searchPlaceholder="搜索计划编号 / 路线 / 负责人"
            onAdd={canInspection ? () => setInspForm({ route: "", frequency: "每周", nextAt: "", assignee: "", active: true }) : undefined}
            addLabel="新增巡检计划"
          />
          <DataTable rowKey={(p: InspectionPlan) => p.planNo} columns={inspectionCols} rows={inspection.data?.list} loading={inspection.isLoading}
            empty="暂无巡检计划——巡检工单目前只能手工开；点右上「新增巡检计划」按路线周期自动开单。" />
          {inspection.data && <Pagination page={page} size={SIZE} total={inspection.data.total} onPage={setPage} />}
        </>
      )}

      {view === "list" && (
        <>
          <DataTable rowKey={(w: WorkOrder) => w.woNo} columns={cols} rows={list.data?.list} loading={list.isLoading}
            empty="没有符合条件的工单——可能是筛选条件太窄，或告警/投诉尚未转工单；换个状态筛选，或点右上「新建工单」。" />
          {list.data && <Pagination page={page} size={SIZE} total={list.data.total} onPage={setPage} />}
        </>
      )}

      {view === "board" && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-5">
          {BOARD_COLS.map((col) => {
            const items = rows.filter((w) => w.status === col.key);
            return (
              <div key={col.key} className="rounded-lg bg-muted/40 p-2">
                <div className="mb-2 flex items-center justify-between px-1 text-sm font-medium">
                  <span>{col.label}</span>
                  <Badge tone="muted">{items.length}</Badge>
                </div>
                <div className="space-y-2">
                  {items.map((w) => (
                    <Card key={w.woNo} className="p-3 text-sm">
                      <div className="flex items-center justify-between">
                        <button type="button" className="font-medium tabular-nums underline-offset-2 hover:underline" onClick={() => setDetail(w)}>{w.woNo}</button>
                        <Badge tone={PRIO[w.priority][1]}>{PRIO[w.priority][0]}</Badge>
                      </div>
                      <div className="mt-1 text-xs text-muted-foreground">{WO_TYPE_LABEL[w.type]} · {w.cabinetNo}</div>
                      <div className="mt-1 text-xs text-muted-foreground">{w.description}</div>
                      <div className="mt-2 flex items-center justify-between gap-2">
                        <span className="text-xs text-muted-foreground">{w.handlerName ?? w.assigneeName ?? "未派单"}</span>
                        {/* 看板不做拖拽（静态导出下拖拽库成本高收益低），用「下一步动作」按钮改状态 */}
                        <ActionButtons w={w} max={2} />
                      </div>
                    </Card>
                  ))}
                  {items.length === 0 && <div className="px-1 py-4 text-center text-xs text-muted-foreground">—</div>}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 开单 */}
      <FormDrawer
        open={!!woForm}
        onOpenChange={(o) => !o && setWoForm(null)}
        titleNew="新建工单"
        titleEdit="新建工单"
        isEdit={false}
        fields={WO_FIELDS}
        value={(woForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setWoForm(v as Partial<WorkOrderDraft>)}
        onSubmit={() => woForm && doCreate.mutate(woForm as WorkOrderDraft)}
        submitting={doCreate.isPending}
      />

      {/* 派单 */}
      <Drawer
        open={!!dispatch}
        onOpenChange={(o) => !o && setDispatch(null)}
        title={`派单 ${dispatch?.woNo ?? ""}`}
        desc={dispatch ? `${WO_TYPE_LABEL[dispatch.type]} · ${dispatch.description}` : ""}
        footer={
          <>
            <Button variant="outline" onClick={() => setDispatch(null)}>取消</Button>
            <Button disabled={doDispatch.isPending || !assignee} onClick={() => dispatch && doDispatch.mutate({ no: dispatch.woNo, assignee })}>确认派单</Button>
          </>
        }
      >
        <Field label="柜机 / 点位">{dispatch?.cabinetNo} · {dispatch?.locationName}</Field>
        {dispatch?.rejectReason && <Field label="上次驳回原因">{dispatch.rejectReason}（已驳回 {dispatch.rejectCount ?? 1} 次）</Field>}
        <Field label="指派给">
          <Select className="w-full" value={assignee} onChange={(e) => setAssignee(e.target.value)}>
            {STAFF.map((s) => <option key={s} value={s}>{s}</option>)}
          </Select>
        </Field>
      </Drawer>

      {/* 处理 / 完成：处理说明必填，换件记录可选 */}
      <Drawer
        open={!!handle}
        onOpenChange={(o) => !o && setHandle(null)}
        title={`${handle?.action === "complete" ? "完成工单" : "提交处理结果"} ${handle?.wo.woNo ?? ""}`}
        desc={handle?.action === "complete" ? "提交后工单进入「已完成」，等待验收关单" : "记录处理进展，工单仍留在「处理中」，可多次提交"}
        footer={
          handle && (
            <>
              <Button variant="outline" onClick={() => setHandle(null)}>取消</Button>
              <Button
                disabled={doHandle.isPending || !handleNote.trim()}
                onClick={() => doHandle.mutate({ no: handle.wo.woNo, action: handle.action, handleNote, partsReplaced: parts })}
              >{handle.action === "complete" ? "确认完成" : "提交"}</Button>
            </>
          )
        }
      >
        {handle && (
          <>
            <Field label="柜机 / 点位">{handle.wo.cabinetNo} · {handle.wo.locationName}</Field>
            <Field label="问题描述">{handle.wo.description}</Field>
            <Field label="处理说明（必填）">
              <textarea
                rows={4} value={handleNote} onChange={(e) => setHandleNote(e.target.value)}
                placeholder="到场时间、排查过程、处理动作、复测结果"
                className="flex w-full resize-y rounded-lg bg-secondary px-3.5 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </Field>
            <Field label="换件记录（可选）">
              <Input value={parts} placeholder="如：锁扣模块 ×1、充电宝 PB1023 换出" onChange={(e) => setParts(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      {/* 验收关单：结论必填（终态，无结论无从追责） */}
      <Drawer
        open={!!closing}
        onOpenChange={(o) => !o && setClosing(null)}
        title={`验收关单 ${closing?.woNo ?? ""}`}
        desc="关单是终态：确认现场处理结果已复核，验收结论会随工单永久留痕"
        footer={
          closing && (
            <>
              <Button variant="outline" onClick={() => setClosing(null)}>取消</Button>
              <Button disabled={doClose.isPending || !auditResult} onClick={() => doClose.mutate({ no: closing.woNo, auditResult, auditNote })}>确认关单</Button>
            </>
          )
        }
      >
        {closing && (
          <>
            <Field label="柜机 / 点位">{closing.cabinetNo} · {closing.locationName}</Field>
            <Field label="处理人 / 完成时间">{closing.handlerName ?? "-"} · {closing.completedAt ? fmtTime(closing.completedAt) : "-"}</Field>
            <Field label="处理说明">{closing.handleNote ?? "-"}</Field>
            <Field label="换件记录">{closing.partsReplaced ?? "无"}</Field>
            <Field label="验收结论（必填）">
              <Select className="w-full" value={auditResult} onChange={(e) => setAuditResult(e.target.value as WoAuditResult)}>
                <option value="PASS">{AUDIT_LABEL.PASS}</option>
                <option value="PASS_WITH_ISSUE">{AUDIT_LABEL.PASS_WITH_ISSUE}</option>
              </Select>
            </Field>
            <Field label={auditResult === "PASS_WITH_ISSUE" ? "遗留问题说明（建议填写）" : "验收备注"}>
              <Input value={auditNote} placeholder="如：抽检一次弹出正常；遗留：广告屏仍偶发花屏" onChange={(e) => setAuditNote(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      {/* 驳回退回：原因为空则提交禁用（沿用退款审批口径） */}
      <Drawer
        open={!!rejecting}
        onOpenChange={(o) => !o && setRejecting(null)}
        title={`${rejectMode === "rework" ? "验收不合格退回返工" : "驳回"} ${rejecting?.woNo ?? ""}`}
        desc={rejectMode === "rework"
          ? "退回后工单回到「处理中」，处理人不变，无需重新派单"
          : "驳回后工单退回「待派单」，处理人被清空，需重新派单"}
        footer={
          rejecting && (
            <>
              <Button variant="outline" onClick={() => setRejecting(null)}>取消</Button>
              <Button
                variant="destructive"
                disabled={doReject.isPending || doRework.isPending || !rejectReason.trim()}
                onClick={() => (rejectMode === "rework" ? doRework : doReject).mutate({ no: rejecting.woNo, reason: rejectReason })}
              >{rejectMode === "rework" ? "确认退回返工" : "确认驳回"}</Button>
            </>
          )
        }
      >
        {rejecting && (
          <>
            <Field label="当前处理人">{rejecting.handlerName ?? rejecting.assigneeName ?? "-"}</Field>
            <Field label="驳回原因（必填）">
              <Input value={rejectReason} placeholder="说明退回理由，回写给派单人" onChange={(e) => setRejectReason(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      {/* 详情：全链路留痕 + 当前状态下的全部可执行动作 */}
      <Drawer
        open={!!detail}
        onOpenChange={(o) => !o && setDetail(null)}
        title={`工单 ${detail?.woNo ?? ""}`}
        desc="从开单到关单的完整留痕"
        footer={detail && <ActionButtons w={detail} />}
      >
        {detail && (
          <>
            <Field label="状态 / 优先级"><WoStatusBadge s={detail.status} /> <Badge tone={PRIO[detail.priority][1]}>{PRIO[detail.priority][0]}</Badge></Field>
            <Field label="类型 / 来源">{WO_TYPE_LABEL[detail.type]} · {SOURCE_LABEL[detail.source]}{detail.sourceNo ? ` · ${detail.sourceNo}` : ""}</Field>
            <Field label="柜机 / 点位">{detail.cabinetNo} · {detail.locationName}</Field>
            <Field label="问题描述">{detail.description}</Field>
            <Field label="创建 / 期望完成">{fmtTime(detail.createdAt)} · {detail.expectedAt ? fmtTime(detail.expectedAt) : "未设定"}</Field>
            <Field label="派单">{detail.assigneeName ? `${detail.assigneeName} · ${detail.dispatchedAt ? fmtTime(detail.dispatchedAt) : "-"}` : "未派单"}</Field>
            <Field label="接单">{detail.acceptedAt ? `${detail.handlerName ?? "-"} · ${fmtTime(detail.acceptedAt)}` : "未接单"}</Field>
            <Field label="处理">{detail.handledAt ? `${detail.handlerName ?? "-"} · ${fmtTime(detail.handledAt)}` : "未处理"}</Field>
            <Field label="处理说明">{detail.handleNote ?? "-"}</Field>
            <Field label="换件记录">{detail.partsReplaced ?? "无"}</Field>
            <Field label="完成时间">{detail.completedAt ? fmtTime(detail.completedAt) : "未完成"}</Field>
            <Field label="验收">{detail.auditedAt ? `${detail.auditorName ?? "-"} · ${fmtTime(detail.auditedAt)} · ${detail.auditResult ? AUDIT_LABEL[detail.auditResult] : "-"}` : "未验收"}</Field>
            <Field label="验收备注">{detail.auditNote ?? "-"}</Field>
            {!!detail.rejectCount && <Field label="驳回记录">{`已驳回 ${detail.rejectCount} 次；最近原因：${detail.rejectReason ?? "-"}`}</Field>}
          </>
        )}
      </Drawer>

      <FormDrawer
        open={!!slaForm}
        onOpenChange={(o) => !o && setSlaForm(null)}
        titleNew="新增 SLA"
        titleEdit={`编辑 SLA ${slaForm?.slaNo ?? ""}`}
        isEdit={!!slaForm?.slaNo}
        fields={SLA_FIELDS}
        value={(slaForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setSlaForm(v as Partial<SlaRule>)}
        onSubmit={() => slaForm && saveSla.mutate(slaForm)}
        submitting={saveSla.isPending}
      />

      <FormDrawer
        open={!!inspForm}
        onOpenChange={(o) => !o && setInspForm(null)}
        titleNew="新增巡检计划"
        titleEdit={`编辑巡检计划 ${inspForm?.planNo ?? ""}`}
        isEdit={!!inspForm?.planNo}
        fields={INSPECTION_FIELDS}
        value={(inspForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setInspForm(v as Partial<InspectionPlan>)}
        onSubmit={() => inspForm && saveInspection.mutate(inspForm)}
        submitting={saveInspection.isPending}
      />
    </div>
  );
}

export default function WorkOrdersPage() {
  return <Suspense fallback={null}><WorkOrdersInner /></Suspense>;
}
