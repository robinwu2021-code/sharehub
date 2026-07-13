"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
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
import type { WorkOrder, SlaRule, InspectionPlan } from "@/lib/types";

const SIZE = 10;
const PRIO: Record<string, [string, "danger" | "warning" | "muted"]> = { HIGH: ["高", "danger"], MEDIUM: ["中", "warning"], LOW: ["低", "muted"] };
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

const WO_TYPE_OPTIONS = Object.entries(WO_TYPE_LABEL).map(([value, label]) => ({ value, label }));
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
  const [dispatch, setDispatch] = useState<WorkOrder | null>(null);
  const [assignee, setAssignee] = useState(STAFF[0]);
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

  const doDispatch = useMutation({
    mutationFn: (v: { no: string; assignee: string }) => api.dispatchWorkOrder(v.no, v.assignee),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["workorders"] }); qc.invalidateQueries({ queryKey: ["workorders-board"] }); setDispatch(null); },
  });
  const canDispatch = allow("workorder:wo:dispatch");
  const canSla = allow("workorder:sla:update");
  const canInspection = allow("workorder:inspection:update");

  const saveSla = useMutation({
    mutationFn: (v: Partial<SlaRule>) => api.saveSlaRule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["sla-rules"] }); notify.success(t("common.success")); setSlaForm(null); },
  });
  const saveInspection = useMutation({
    mutationFn: (v: Partial<InspectionPlan>) => api.saveInspectionPlan(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["inspection-plans"] }); notify.success(t("common.success")); setInspForm(null); },
  });

  const cols: Column<WorkOrder>[] = [
    { header: "工单号", cell: (w) => <span className="font-medium">{w.woNo}</span> },
    { header: "类型", cell: (w) => WO_TYPE_LABEL[w.type] },
    { header: "柜机", cell: (w) => w.cabinetNo },
    { header: "点位", cell: (w) => <span className="text-muted-foreground">{w.locationName}</span> },
    { header: "优先级", cell: (w) => <Badge tone={PRIO[w.priority][1]}>{PRIO[w.priority][0]}</Badge> },
    { header: "状态", cell: (w) => <WoStatusBadge s={w.status} /> },
    { header: "处理人", cell: (w) => <span className="text-muted-foreground">{w.assigneeName ?? "未派单"}</span> },
    { header: "创建", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.createdAt)}</span> },
    {
      header: "操作",
      cell: (w) => w.status === "CREATED" && canDispatch ? (
        <Button size="sm" onClick={() => { setDispatch(w); setAssignee(STAFF[0]); }}>派单</Button>
      ) : <span className="text-muted-foreground">-</span>,
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
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input className="w-56" placeholder="搜索工单号 / 柜机" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} />
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
        </div>
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
          <DataTable rowKey={(s: SlaRule) => s.slaNo} columns={slaCols} rows={sla.data?.list} loading={sla.isLoading} />
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
          <DataTable rowKey={(p: InspectionPlan) => p.planNo} columns={inspectionCols} rows={inspection.data?.list} loading={inspection.isLoading} />
          {inspection.data && <Pagination page={page} size={SIZE} total={inspection.data.total} onPage={setPage} />}
        </>
      )}

      {view === "list" && (
        <>
          <DataTable rowKey={(w: WorkOrder) => w.woNo} columns={cols} rows={list.data?.list} loading={list.isLoading} />
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
                        <span className="font-medium">{w.woNo}</span>
                        <Badge tone={PRIO[w.priority][1]}>{PRIO[w.priority][0]}</Badge>
                      </div>
                      <div className="mt-1 text-xs text-muted-foreground">{WO_TYPE_LABEL[w.type]} · {w.cabinetNo}</div>
                      <div className="mt-1 text-xs text-muted-foreground">{w.description}</div>
                      <div className="mt-2 flex items-center justify-between">
                        <span className="text-xs text-muted-foreground">{w.assigneeName ?? "未派单"}</span>
                        {w.status === "CREATED" && canDispatch && (
                          <Button size="sm" onClick={() => { setDispatch(w); setAssignee(STAFF[0]); }}>派单</Button>
                        )}
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

      <Drawer
        open={!!dispatch}
        onOpenChange={(o) => !o && setDispatch(null)}
        title={`派单 ${dispatch?.woNo ?? ""}`}
        desc={dispatch ? `${WO_TYPE_LABEL[dispatch.type]} · ${dispatch.description}` : ""}
        footer={
          <>
            <Button variant="outline" onClick={() => setDispatch(null)}>取消</Button>
            <Button disabled={doDispatch.isPending} onClick={() => dispatch && doDispatch.mutate({ no: dispatch.woNo, assignee })}>确认派单</Button>
          </>
        }
      >
        <Field label="柜机 / 点位">{dispatch?.cabinetNo} · {dispatch?.locationName}</Field>
        <Field label="指派给">
          <Select className="w-full" value={assignee} onChange={(e) => setAssignee(e.target.value)}>
            {STAFF.map((s) => <option key={s} value={s}>{s}</option>)}
          </Select>
        </Field>
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
