"use client";

// 到仓待检（设备管理 › 入库与质检，V192 / V193）：到货登记 → 逐台质检 → 合格入库 / 不合格处置。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { Intake, IntakeDisposal, IntakeItem, IntakeItemRef } from "@/lib/types";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { QcDrawer, type QcTarget } from "@/components/device/qc";

const STATUS: StatusMap<"OPEN" | "DONE"> = { OPEN: { label: "处理中", tone: "warning" }, DONE: { label: "已完成", tone: "success" } };
const QC: StatusMap<"PENDING" | "PASSED" | "FAILED"> = {
  PENDING: { label: "待检", tone: "muted" }, PASSED: { label: "合格", tone: "success" }, FAILED: { label: "不合格", tone: "danger" },
};
const DISPOSITION: StatusMap<"PENDING" | "INBOUND" | "RETURN_SUPPLIER" | "SCRAP"> = {
  PENDING: { label: "待处理", tone: "muted" }, INBOUND: { label: "已入库", tone: "success" },
  RETURN_SUPPLIER: { label: "退供应商", tone: "warning" }, SCRAP: { label: "报废", tone: "danger" },
};
const TYPE_LABEL = { CABINET: "机柜", POWERBANK: "充电宝" } as const;
const lines = (v: unknown) => String(v ?? "").split(/[\s,，]+/).map((s) => s.trim()).filter(Boolean);
const keyOf = (i: IntakeItem) => `${i.itemType}:${i.itemNo}`;
const refOf = (k: string): IntakeItemRef => { const [itemType, itemNo] = k.split(":"); return { itemType: itemType as IntakeItemRef["itemType"], itemNo }; };

export function IntakesTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const canWrite = allow("device:inventory:inbound");
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [warehouseNo, setWarehouseNo] = useState("");
  const [form, setForm] = useState<Record<string, unknown> | null>(null);
  const [openNo, setOpenNo] = useState<string | null>(null);

  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const whOptions = (whQ.data ?? []).map((w) => ({ value: w.warehouseNo, label: w.name }));
  const q = useQuery({
    queryKey: ["intakes", paging.page, paging.size, keyword, status, warehouseNo],
    queryFn: () => api.listIntakes({ page: paging.page, size: paging.size, keyword, status: (status || undefined) as Intake["status"] | undefined, warehouseNo: warehouseNo || undefined }),
    placeholderData: keepPreviousData,
  });

  const create = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.createIntake({
      warehouseNo: String(v.warehouseNo ?? ""), sourceRef: String(v.sourceRef ?? "") || undefined, note: String(v.note ?? "") || undefined,
      cabinetNos: lines(v.cabinetNos), powerbankNos: lines(v.powerbankNos),
    }),
    onSuccess: (d) => {
      notify.success(`已登记 ${d.intake.intakeNo}`);
      qc.invalidateQueries({ queryKey: ["intakes"] });
      setForm(null);
      setOpenNo(d.intake.intakeNo);
    },
  });

  /**
   * @form POST /api/ops/inventory/intakes
   */
  const FIELDS: FieldDef[] = [
    { key: "warehouseNo", label: "仓库", type: "select", required: true, options: whOptions },
    { key: "sourceRef", label: "来源单号", maxLength: 64 },
    { key: "cabinetNos", label: "机柜编号", type: "textarea", rows: 4, placeholder: "每行一个，可扫码" },
    { key: "powerbankNos", label: "充电宝编号", type: "textarea", rows: 4, placeholder: "每行一个，可扫码" },
    { key: "note", label: "备注", type: "textarea", rows: 2, maxLength: 512 },
  ];

  const cols: Column<Intake>[] = [
    { header: "单号", cell: (h) => <button type="button" className="font-mono text-primary hover:underline" onClick={() => setOpenNo(h.intakeNo)}>{h.intakeNo}</button> },
    { header: "仓库", cell: (h) => h.warehouseName ?? h.warehouseNo },
    { header: "来源单号", cell: (h) => h.sourceRef ?? <span className="text-muted-foreground">-</span> },
    { header: "到达", className: "whitespace-nowrap", cell: (h) => fmtTime(h.arrivedAt) },
    { header: "台数", className: "text-right tabular-nums", cell: (h) => h.total },
    { header: "待检", className: "text-right tabular-nums", cell: (h) => h.pending },
    { header: "合格", className: "text-right tabular-nums", cell: (h) => h.passed },
    { header: "不合格", className: "text-right tabular-nums", cell: (h) => h.failed },
    { header: "已入库", className: "text-right tabular-nums", cell: (h) => h.inbound },
    { header: "已处置", className: "text-right tabular-nums", cell: (h) => h.disposed },
    { header: "状态", className: "whitespace-nowrap", cell: (h) => <StatusBadge map={STATUS} value={h.status} /> },
  ];

  return (
    <div>
      {!canWrite && <ReadOnlyNotice what="到货登记与入库" perm="device:inventory:inbound" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="单号 / 来源单号"
        onAdd={() => setForm({ warehouseNo: whQ.data?.find((w) => w.isDefault)?.warehouseNo ?? "" })} addLabel="到货登记" canAdd={canWrite}>
        <FilterSelect aria-label="状态" value={status} onChange={(v) => { setStatus(v); paging.reset(); }} options={STATUS} allLabel="全部状态" />
        <FilterSelect aria-label="仓库" value={warehouseNo} onChange={(v) => { setWarehouseNo(v); paging.reset(); }} options={whOptions} allLabel="全部仓库" />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(h: Intake) => h.intakeNo} columns={cols}
        empty={keyword || status || warehouseNo ? "没有匹配的待检单" : "暂无待检单"} />
      <FormDrawer open={!!form} onOpenChange={(o) => !o && setForm(null)} titleNew="到货登记" titleEdit="到货登记" isEdit={false}
        fields={FIELDS} value={form ?? {}} onChange={setForm} onSubmit={() => form && create.mutate(form)} submitting={create.isPending} />
      <IntakeDrawer intakeNo={openNo} onClose={() => setOpenNo(null)} />
    </div>
  );
}

function IntakeDrawer({ intakeNo, onClose }: { intakeNo: string | null; onClose: () => void }) {
  const qc = useQueryClient();
  const allow = useCan();
  const canWrite = allow("device:inventory:inbound");
  const canQc = allow("device:qc:inspect");
  const [selected, setSelected] = useState<string[]>([]);
  const [qcTarget, setQcTarget] = useState<QcTarget | null>(null);
  const [dispose, setDispose] = useState<Record<string, unknown> | null>(null);
  const q = useQuery({ queryKey: ["intake", intakeNo], queryFn: () => api.getIntake(intakeNo!), enabled: !!intakeNo });
  const d = q.data;
  const open = d?.intake.status === "OPEN";
  const byKey = new Map((d?.items ?? []).map((i) => [keyOf(i), i]));
  const passed = selected.filter((k) => byKey.get(k)?.disposition === "PENDING" && byKey.get(k)?.qcResult === "PASSED");
  const failed = selected.filter((k) => byKey.get(k)?.disposition === "PENDING" && byKey.get(k)?.qcResult === "FAILED");

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["intake", intakeNo] });
    qc.invalidateQueries({ queryKey: ["intakes"] });
    setSelected([]);
  };
  const inbound = useMutation({
    mutationFn: () => api.inboundIntake(intakeNo!, passed.map(refOf)),
    onSuccess: () => { notify.success(`已入库 ${passed.length} 台`); refresh(); },
  });
  const disposeM = useMutation({
    mutationFn: (v: Record<string, unknown>) => api.disposeIntake(intakeNo!, {
      items: failed.map(refOf), disposition: v.disposition as IntakeDisposal, note: String(v.note ?? ""),
    }),
    onSuccess: () => { notify.success(`已处置 ${failed.length} 台`); setDispose(null); refresh(); },
  });

  /**
   * @form POST /api/ops/inventory/intakes/{intakeNo}/dispose
   */
  const DISPOSE_FIELDS: FieldDef[] = [
    { key: "disposition", label: "处置", type: "select", required: true,
      options: [{ value: "RETURN_SUPPLIER", label: "退供应商" }, { value: "SCRAP", label: "报废" }] },
    { key: "note", label: "说明", type: "textarea", required: true, rows: 3, maxLength: 512 },
  ];

  const cols: Column<IntakeItem>[] = [
    { header: "类型", className: "whitespace-nowrap", cell: (i) => TYPE_LABEL[i.itemType] },
    { header: "设备", cell: (i) => <span className="font-mono">{i.itemNo}</span> },
    { header: "SN", cell: (i) => i.sn ?? "-" },
    { header: "型号", cell: (i) => i.model ?? "-" },
    { header: "质检", className: "whitespace-nowrap", cell: (i) => <StatusBadge map={QC} value={i.qcResult} /> },
    { header: "处置", className: "whitespace-nowrap", cell: (i) => <StatusBadge map={DISPOSITION} value={i.disposition} /> },
    { header: "", className: "whitespace-nowrap", cell: (i) => (open && canQc && i.disposition === "PENDING"
      ? <Button size="sm" variant="outline" onClick={() => setQcTarget({ itemType: i.itemType, itemNo: i.itemNo })}>质检</Button>
      : null) },
  ];

  return (
    <>
      <Drawer open={!!intakeNo} onOpenChange={(o) => { if (!o) { setSelected([]); onClose(); } }} width="w-[880px]"
        title={d ? `${d.intake.intakeNo} · ${d.intake.warehouseName ?? d.intake.warehouseNo}` : intakeNo ?? ""}
        footer={open && canWrite ? (
          <div className="flex gap-2">
            <Button disabled={!passed.length || inbound.isPending} onClick={() => inbound.mutate()}>入库（{passed.length}）</Button>
            <Button variant="outline" disabled={!failed.length} onClick={() => setDispose({})}>处置（{failed.length}）</Button>
          </div>
        ) : undefined}>
        <DataTable rowKey={keyOf} columns={cols} rows={d?.items} loading={q.isLoading} error={q.error} onRetry={q.refetch}
          selectable={open && canWrite} selectedKeys={selected} onSelectedChange={setSelected} empty="无设备" />
      </Drawer>
      <QcDrawer target={qcTarget} onClose={() => setQcTarget(null)} intakeNo={intakeNo ?? undefined} />
      <FormDrawer open={!!dispose} onOpenChange={(o) => !o && setDispose(null)} titleNew={`处置 ${failed.length} 台`} titleEdit=""
        isEdit={false} fields={DISPOSE_FIELDS} value={dispose ?? {}} onChange={setDispose}
        onSubmit={() => dispose && disposeM.mutate(dispose)} submitting={disposeM.isPending} />
    </>
  );
}
