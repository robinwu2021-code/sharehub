"use client";

// 物料单据（库存管理 › 出入库，V209）：备件、耗材、其他整机的入库 / 领用 / 报废 / 退供应商 / 调拨。
// 建单即过账（同处置出库），纠错走反向单据。数量物料填数量；逐件物料录序列号（每行一个，可扫码）。
// 入库类用途判 device:inventory:inbound，出库与调拨判 device:inventory:transfer。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { MaterialDoc, MaterialDocKind, MaterialDocLine, MaterialDocPurpose, MaterialScrapReason } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { PagedTable } from "@/components/ui/paged-table";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Notice } from "@/components/ui/notice";
import { activeWarehouses } from "@/components/inventory/warehouses";
import { INBOUND_PURPOSES, KIND, OUTBOUND_PURPOSES, PURPOSE_LABEL, SCRAP_REASON } from "@/components/inventory/material-maps";

interface LineDraft { materialNo: string; qty: string; serials: string; unitCost: string }
interface DocDraft {
  purpose: MaterialDocPurpose; warehouseNo: string; toWarehouseNo: string; supplierNo: string; reasonCode: string;
  receiverNo: string; woNo: string; currency: string; note: string; lines: LineDraft[];
}
const emptyLine = (): LineDraft => ({ materialNo: "", qty: "", serials: "", unitCost: "" });

export function MaterialDocsTab() {
  const allow = useCan();
  const canIn = allow("device:inventory:inbound");
  const canOut = allow("device:inventory:transfer");
  const purposes = [...(canIn ? INBOUND_PURPOSES : []), ...(canOut ? OUTBOUND_PURPOSES : [])];
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [kind, setKind] = useState("");
  const [draft, setDraft] = useState<DocDraft | null>(null);
  const [openNo, setOpenNo] = useState<string | null>(null);

  const whQ = useQuery({ queryKey: ["warehouses"], queryFn: () => api.listWarehouses() });
  const engineQ = useQuery({ queryKey: ["inventory-engine"], queryFn: () => api.getInventoryEngine() });
  const service = engineQ.data?.engine === "SERVICE";
  const q = useQuery({
    queryKey: ["material-docs", paging.page, paging.size, keyword, kind],
    queryFn: () => api.listMaterialDocs({ page: paging.page, size: paging.size, keyword, kind: (kind || undefined) as MaterialDocKind | undefined }),
    placeholderData: keepPreviousData,
  });

  const where = (d: MaterialDoc) => (d.kind === "TRANSFER" ? `${d.warehouseName ?? d.warehouseNo} → ${d.toWarehouseName ?? d.toWarehouseNo}` : d.warehouseName ?? d.warehouseNo);
  const party = (d: MaterialDoc) => d.supplierName ?? (d.reasonCode ? SCRAP_REASON[d.reasonCode] : null)
    ?? [d.receiverNo, d.woNo].filter(Boolean).join(" · ") ?? "-";
  const cols: Column<MaterialDoc>[] = [
    { header: "单号", cell: (d) => <button type="button" className="font-mono text-primary hover:underline" onClick={() => setOpenNo(d.docNo)}>{d.docNo}</button> },
    { header: "类型", className: "whitespace-nowrap", cell: (d) => <StatusBadge map={KIND} value={d.kind} /> },
    { header: "用途", className: "whitespace-nowrap", cell: (d) => PURPOSE_LABEL[d.purpose] },
    { header: "仓库", className: "whitespace-nowrap", cell: (d) => where(d) },
    { header: "对象", cell: (d) => party(d) || <span className="text-muted-foreground">-</span> },
    { header: "数量", className: "text-right tabular-nums whitespace-nowrap", cell: (d) => d.totalQty },
    { header: "说明", className: "min-w-40", cell: (d) => <span className="txt-caption">{d.note}</span> },
    { header: "时间", className: "whitespace-nowrap", cell: (d) => (d.createdAt ? fmtTime(d.createdAt) : "-") },
  ];

  return (
    <div>
      {engineQ.data && !service && <Notice>物料的账记在独立进销存（库存服务）：切到独立进销存引擎（库存设置 › 库存引擎 = SERVICE）后可用。物料档案不受影响，可先建档。</Notice>}
      {!purposes.length && <ReadOnlyNotice what="物料出入库" perm="device:inventory:inbound" note="不能建物料单据" className="mb-3" />}
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="单号 / 说明 / 工单 / 序列号"
        onAdd={() => setDraft({ purpose: purposes[0], warehouseNo: whQ.data?.find((w) => w.isDefault)?.warehouseNo ?? "", toWarehouseNo: "",
          supplierNo: "", reasonCode: "", receiverNo: "", woNo: "", currency: "AED", note: "", lines: [emptyLine()] })}
        addLabel="新建物料单据" canAdd={purposes.length > 0 && service}>
        <FilterSelect aria-label="类型" value={kind} onChange={(v) => { setKind(v); paging.reset(); }} options={KIND} allLabel="全部类型" />
      </Toolbar>
      <PagedTable query={q} paging={paging} rowKey={(d: MaterialDoc) => d.docNo} columns={cols}
        empty={keyword || kind ? "没有符合条件的单据。" : "还没有物料单据。备件采购到货、维修领用、耗材报废都在这里记账。"} />
      {draft && <DocDrawer draft={draft} purposes={purposes} warehouses={activeWarehouses(whQ.data).map((w) => ({ value: w.warehouseNo, label: w.name }))}
        onChange={setDraft} onClose={() => setDraft(null)} onDone={(no) => { setDraft(null); setOpenNo(no); }} />}
      <DetailDrawer docNo={openNo} onClose={() => setOpenNo(null)} />
    </div>
  );
}

function DocDrawer({ draft, purposes, warehouses, onChange, onClose, onDone }: {
  draft: DocDraft; purposes: MaterialDocPurpose[]; warehouses: { value: string; label: string }[];
  onChange: (d: DocDraft) => void; onClose: () => void; onDone: (docNo: string) => void;
}) {
  const qc = useQueryClient();
  const [requestKey] = useState(() => crypto.randomUUID());
  const matQ = useQuery({ queryKey: ["materials", "all"], queryFn: () => api.listMaterials({ page: 1, size: UNPAGED_SIZE }) });
  const spQ = useQuery({ queryKey: ["suppliers", "active"], queryFn: () => api.listSuppliers({ page: 1, size: UNPAGED_SIZE }) });
  const materials = useMemo(() => (matQ.data?.list ?? []).filter((m) => !m.builtin), [matQ.data]);
  const matOf = (no: string) => materials.find((m) => m.materialNo === no);
  const p = draft.purpose;
  const inbound = INBOUND_PURPOSES.includes(p);
  const set = (patch: Partial<DocDraft>) => onChange({ ...draft, ...patch });
  const setLine = (i: number, patch: Partial<LineDraft>) => set({ lines: draft.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  const create = useMutation({
    mutationFn: () => {
      const lines: MaterialDocLine[] = draft.lines.filter((l) => l.materialNo).map((l) => {
        const serial = matOf(l.materialNo)?.trackingMode === "SERIAL";
        const sns = l.serials.split(/[\s,，]+/).map((s) => s.trim()).filter(Boolean);
        return { materialNo: l.materialNo, qty: serial ? sns.length : Number(l.qty), serialNos: serial ? sns : undefined,
          unitCost: inbound && l.unitCost ? Number(l.unitCost) : null };
      });
      return api.createMaterialDoc({
        requestKey,
        purpose: p, warehouseNo: draft.warehouseNo, note: draft.note, lines,
        toWarehouseNo: p === "TRANSFER" ? draft.toWarehouseNo || null : null,
        supplierNo: p === "PURCHASE" || p === "RETURN_SUPPLIER" ? draft.supplierNo || null : null,
        reasonCode: p === "SCRAP" ? (draft.reasonCode || null) as MaterialScrapReason | null : null,
        receiverNo: p === "REQUISITION" ? draft.receiverNo || null : null,
        woNo: p === "REQUISITION" ? draft.woNo || null : null,
        currency: lines.some((l) => l.unitCost != null) ? draft.currency || null : null,
      });
    },
    onSuccess: (d) => {
      notify.success(`已过账 ${d.docNo}（${d.totalQty}）`);
      qc.invalidateQueries({ queryKey: ["material-docs"] });
      qc.invalidateQueries({ queryKey: ["material-balances"] });
      qc.invalidateQueries({ queryKey: ["materials"] });
      onDone(d.docNo);
    },
  });

  return (
    <Drawer open onOpenChange={(o) => !o && onClose()} width="w-[720px]" title="新建物料单据" desc="提交即过账，不能修改；记错了用反向单据冲回"
      footer={<Button disabled={create.isPending || !draft.note.trim() || !draft.lines.some((l) => l.materialNo)} onClick={() => create.mutate()}>过账</Button>}>
      <div className="grid grid-cols-2 gap-x-4">
        <Field label="用途">
          <Select aria-label="用途" value={p} onChange={(e) => set({ purpose: e.target.value as MaterialDocPurpose })}>
            {purposes.map((x) => <option key={x} value={x}>{PURPOSE_LABEL[x]}</option>)}
          </Select>
        </Field>
        <Field label={p === "TRANSFER" ? "调出仓" : inbound ? "入库仓" : "出库仓"}>
          <Select aria-label="仓库" value={draft.warehouseNo} onChange={(e) => set({ warehouseNo: e.target.value })}>
            <option value="">请选择</option>
            {warehouses.map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
          </Select>
        </Field>
        {p === "TRANSFER" && (
          <Field label="调入仓">
            <Select aria-label="调入仓" value={draft.toWarehouseNo} onChange={(e) => set({ toWarehouseNo: e.target.value })}>
              <option value="">请选择</option>
              {warehouses.filter((w) => w.value !== draft.warehouseNo).map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
            </Select>
          </Field>
        )}
        {(p === "PURCHASE" || p === "RETURN_SUPPLIER") && (
          <Field label="供应商">
            <Select aria-label="供应商" value={draft.supplierNo} onChange={(e) => set({ supplierNo: e.target.value })}>
              <option value="">请选择</option>
              {(spQ.data?.list ?? []).map((s) => <option key={s.supplierNo} value={s.supplierNo}>{s.name}</option>)}
            </Select>
          </Field>
        )}
        {p === "SCRAP" && (
          <Field label="报废原因">
            <Select aria-label="报废原因" value={draft.reasonCode} onChange={(e) => set({ reasonCode: e.target.value })}>
              <option value="">请选择</option>
              {Object.entries(SCRAP_REASON).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </Select>
          </Field>
        )}
        {p === "REQUISITION" && (
          <>
            <Field label="领用人（工号）"><Input aria-label="领用人" value={draft.receiverNo} onChange={(e) => set({ receiverNo: e.target.value })} /></Field>
            <Field label="关联工单"><Input aria-label="关联工单" placeholder="维修 / 装机工单号" value={draft.woNo} onChange={(e) => set({ woNo: e.target.value })} /></Field>
          </>
        )}
        {inbound && (
          <Field label="币种（填了单价才需要）">
            <Select aria-label="币种" value={draft.currency} onChange={(e) => set({ currency: e.target.value })}>
              {["AED", "USD", "CNY"].map((c) => <option key={c} value={c}>{c}</option>)}
            </Select>
          </Field>
        )}
      </div>

      <Field label="物料明细">
        <div className="space-y-3">
          {draft.lines.map((l, i) => {
            const m = matOf(l.materialNo);
            const serial = m?.trackingMode === "SERIAL";
            return (
              <div key={i} className="grid grid-cols-[1fr_8rem_7rem_auto] items-start gap-2">
                <Select aria-label={`第 ${i + 1} 行物料`} value={l.materialNo} onChange={(e) => setLine(i, { materialNo: e.target.value })}>
                  <option value="">选择物料</option>
                  {materials.filter((x) => !x.archivedAt).map((x) => <option key={x.materialNo} value={x.materialNo}>{x.name} · {x.materialNo}</option>)}
                </Select>
                {serial
                  ? <Textarea aria-label={`第 ${i + 1} 行序列号`} rows={2} placeholder="序列号，每行一个" value={l.serials} onChange={(v) => setLine(i, { serials: v })} />
                  : <Input aria-label={`第 ${i + 1} 行数量`} type="number" min={1} placeholder={`数量${m ? `（${m.uom}）` : ""}`} value={l.qty} onChange={(e) => setLine(i, { qty: e.target.value })} />}
                {inbound
                  ? <Input aria-label={`第 ${i + 1} 行单价`} type="number" min={0} placeholder="单价（可选）" value={l.unitCost} onChange={(e) => setLine(i, { unitCost: e.target.value })} />
                  : <span />}
                <Button size="sm" variant="outline" aria-label="删除这一行" disabled={draft.lines.length === 1}
                  onClick={() => set({ lines: draft.lines.filter((_, j) => j !== i) })}><Trash2 className="size-4" /></Button>
              </div>
            );
          })}
          <Button size="sm" variant="outline" onClick={() => set({ lines: [...draft.lines, emptyLine()] })}><Plus className="size-4" /> 加一行</Button>
        </div>
      </Field>
      <Field label="说明">
        <Textarea aria-label="说明" rows={2} maxLength={512} value={draft.note} onChange={(v) => set({ note: v })} />
      </Field>
    </Drawer>
  );
}

function DetailDrawer({ docNo, onClose }: { docNo: string | null; onClose: () => void }) {
  const q = useQuery({ queryKey: ["material-doc", docNo], queryFn: () => api.getMaterialDoc(docNo!), enabled: !!docNo });
  const d = q.data;
  const cols: Column<MaterialDocLine>[] = [
    { header: "物料", cell: (l) => <span>{l.materialName ?? l.materialNo} <span className="font-mono txt-caption text-muted-foreground">{l.materialNo}</span></span> },
    { header: "数量", className: "text-right tabular-nums whitespace-nowrap", cell: (l) => l.qty },
    { header: "序列号", cell: (l) => (l.serialNos?.length ? <span className="font-mono txt-caption">{l.serialNos.join("、")}</span> : "-") },
    { header: "单价", className: "text-right tabular-nums whitespace-nowrap", cell: (l) => (l.unitCost != null ? `${l.unitCost.toFixed(2)} ${d?.currency ?? ""}` : "-") },
  ];
  return (
    <Drawer open={!!docNo} onOpenChange={(o) => !o && onClose()} width="w-[720px]"
      title={d ? `${d.docNo} · ${PURPOSE_LABEL[d.purpose]}` : docNo ?? ""} desc={d ? `${d.note} · 经办 ${d.createdBy ?? "-"} · ${d.createdAt ? fmtTime(d.createdAt) : ""}` : undefined}>
      <DataTable rowKey={(l: MaterialDocLine) => l.materialNo} columns={cols} rows={d?.lines} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="无明细" />
    </Drawer>
  );
}
