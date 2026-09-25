"use client";

// 入库质检（批次 5b · 后端 AssetOpsController /qc）：质检抽屉 + 质检记录表。
//
// 质检**只在在库时能做**：已布放的设备出问题走故障 / 维修，不走入库质检。
// PENDING / FAILED 的设备不能发货调拨、也过不了上线门禁的第一项。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { QcItemType, QcRecord, QcReq } from "@/lib/types";
import { QC_STATUS } from "./device-maps";

const RESULT_OPTIONS = [
  { value: "", label: "按检查项判定" },
  { value: "FAILED", label: "判不通过（须写原因）" },
];

/**
 * 机柜质检：通电 + 仓位自检。结论默认按检查项判；人可以把「过了」判成不过（写原因），
 * 不能把「不过」判成过 —— 所以结论下拉里**没有「判通过」**，给了也只会被后端拒。
 *
 * @form POST /api/ops/devices/{cabinetNo}/qc
 */
const cabinetQcFields: FieldDef[] = [
  { key: "powerOn", label: "通电正常", type: "switch" },
  { key: "slotsOk", label: "仓位自检全部通过", type: "switch", help: "逐仓开合一次；有一个仓卡住就算不通过" },
  { key: "result", label: "结论", type: "select", options: RESULT_OPTIONS },
  { key: "note", label: "备注 / 不通过原因", type: "textarea", maxLength: 512,
    help: "检查项没全过、或人工判不通过时必填" },
];

/**
 * 充电宝质检：电量 ≥ 60% 且循环次数 ≤ 500 才算检查项通过（阈值在系统参数里，默认值见后端）。
 * 质检会顺带把实测电量与循环次数回写到台账。
 *
 * @form POST /api/ops/powerbanks/{powerbankNo}/qc
 */
const powerbankQcFields: FieldDef[] = [
  { key: "battery", label: "实测电量（%）", type: "number", required: true, min: 0, max: 100 },
  { key: "cycles", label: "循环次数", type: "number", required: true, min: 0,
    help: "超过上限的宝不宜入库投放，会很快被系统标成「老化待报废」" },
  { key: "result", label: "结论", type: "select", options: RESULT_OPTIONS },
  { key: "note", label: "备注 / 不通过原因", type: "textarea", maxLength: 512,
    help: "检查项没全过、或人工判不通过时必填" },
];

export interface QcTarget {
  itemType: QcItemType;
  itemNo: string;
}

/** 质检抽屉。`target` 为 null 时关闭。 */
export function QcDrawer({ target, onClose }: { target: QcTarget | null; onClose: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<Record<string, unknown>>({});
  useEffect(() => {
    if (target) setForm(target.itemType === "CABINET" ? { powerOn: true, slotsOk: true, result: "" } : { result: "" });
  }, [target]);

  const run = useMutation({
    mutationFn: (t: QcTarget) => {
      const req: QcReq = {
        ...(t.itemType === "CABINET"
          ? { powerOn: !!form.powerOn, slotsOk: !!form.slotsOk }
          : { battery: Number(form.battery), cycles: Number(form.cycles) }),
        result: form.result === "FAILED" ? "FAILED" : null,
        note: String(form.note ?? "").trim() || null,
      };
      return t.itemType === "CABINET" ? api.inspectCabinet(t.itemNo, req) : api.inspectPowerbank(t.itemNo, req);
    },
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["qc-records", r.itemNo] });
      qc.invalidateQueries({ queryKey: ["go-live-gate", r.itemNo] });
      qc.invalidateQueries({ queryKey: ["devices"] });
      if (r.result === "PASSED") notify.success(`${r.itemNo} 质检通过，可以发货调拨 / 上线`);
      else notify.info(`${r.itemNo} 已判质检不通过：不能发货调拨、也不能上线`);
      onClose();
    },
  });

  return (
    <FormDrawer
      open={!!target}
      onOpenChange={(o) => !o && onClose()}
      titleNew={`入库质检 ${target?.itemNo ?? ""}`}
      titleEdit={`入库质检 ${target?.itemNo ?? ""}`}
      isEdit={false}
      fields={target?.itemType === "POWERBANK" ? powerbankQcFields : cabinetQcFields}
      value={form}
      onChange={setForm}
      onSubmit={() => target && run.mutate(target)}
      submitting={run.isPending}
    />
  );
}

const cols: Column<QcRecord>[] = [
  { header: "质检单号", cell: (r) => <span className="tabular-nums">{r.qcNo}</span> },
  { header: "结论", cell: (r) => <StatusBadge map={QC_STATUS} value={r.result} /> },
  {
    header: "检查项",
    cell: (r) => (
      <span className="txt-caption text-muted-foreground">
        {r.itemType === "CABINET"
          ? `通电 ${r.powerOn ? "✓" : "✗"} · 仓位 ${r.slotsOk ? "✓" : "✗"}`
          : `电量 ${r.battery ?? "-"}% · 循环 ${r.cycles ?? "-"}`}
      </span>
    ),
  },
  { header: "备注", cell: (r) => <span className="txt-caption">{r.note ?? "-"}</span> },
  { header: "质检人", cell: (r) => <span className="text-muted-foreground">{r.inspectedBy}</span> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.inspectedAt)}</span> },
];

/** 某台设备的质检记录（新的在前）。 */
export function QcRecords({ itemNo }: { itemNo: string }) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["qc-records", itemNo],
    queryFn: () => api.listQcRecords(itemNo),
    enabled: !!itemNo,
  });
  return (
    <DataTable
      rowKey={(r: QcRecord) => r.qcNo}
      columns={cols}
      rows={data}
      loading={isLoading}
      error={error}
      onRetry={refetch}
      empty="还没有质检记录——质检上线前入库的存量设备按「免检」放行；新到货的设备在库时在这里做入库质检"
    />
  );
}
