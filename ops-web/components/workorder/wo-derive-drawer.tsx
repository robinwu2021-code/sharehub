"use client";

// 巡检派生（后端 derive，D4）：巡检现场发现问题，直接另开维修 / 补宝 / 清洁单，来源挂巡检单号。
// 塞进巡检单的备注里的话，那个问题**不进任何人的待办**，也不被 SLA 计时 —— 等于说了等于没说。
import * as React from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { statusOptions } from "@/components/ui/status-badge";
import { WO_TYPE_LABEL } from "@/components/status";
import { WO_DERIVABLE_TYPES, type WorkOrder } from "@/lib/types";
import { PRIO } from "./wo-meta";

/**
 * 派生子单（字段键 = 后端 DeriveReq）。
 * @form POST /api/ops/work-orders/{woNo}/derive
 */
const DERIVE_FIELDS: FieldDef[] = [
  { key: "type", label: "子单类型", type: "select", required: true,
    options: WO_DERIVABLE_TYPES.map((t) => ({ value: t, label: WO_TYPE_LABEL[t] ?? t })),
    help: "巡检只能派生维修 / 补宝 / 清洁 —— 其余类型走正常开单" },
  { key: "priority", label: "优先级", type: "select", options: statusOptions(PRIO), help: "默认「中」" },
  { key: "cabinetNo", label: "机柜号", placeholder: "留空 = 巡检单上的机柜", maxLength: 40 },
  { key: "description", label: "发现了什么", type: "textarea", rows: 4, required: true, maxLength: 200,
    placeholder: "现象、影响面；接手的人只看这一段就要知道去修什么" },
];

export function WoDeriveDrawer({
  parent, onClose, onDone,
}: {
  parent: WorkOrder | null;
  onClose: () => void;
  onDone: (child: WorkOrder) => void;
}) {
  const [v, setV] = React.useState<Record<string, unknown>>({});
  React.useEffect(() => { if (parent) setV({ type: "FAULT", priority: "MEDIUM", cabinetNo: "", description: "" }); }, [parent]);
  const run = useMutation({
    mutationFn: () => api.deriveWorkOrder(parent!.woNo, {
      type: String(v.type),
      priority: (v.priority as string) || null,
      cabinetNo: String(v.cabinetNo ?? "").trim() || null,
      description: String(v.description ?? "").trim(),
    }),
    onSuccess: onDone,
  });
  return (
    <FormDrawer
      open={!!parent}
      onOpenChange={(o) => !o && onClose()}
      titleNew={`从巡检单 ${parent?.woNo ?? ""} 派生`}
      titleEdit=""
      isEdit={false}
      fields={DERIVE_FIELDS}
      value={v}
      onChange={setV}
      onSubmit={() => run.mutate()}
      submitting={run.isPending}
    />
  );
}
