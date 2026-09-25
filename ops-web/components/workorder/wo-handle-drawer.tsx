"use client";

// 提交处理 / 完工抽屉（方案 §8.5「完工抽屉加必填」）。
//
// 完工的必填项**按工单类型**来，与后端 WoOpsServiceImpl.complete 同一套（woCompleteRules）：
//   维修 / 装机 / 撤机 → 现场照片 ≥ 1 张；维修 → 故障原因分类；撤机 → 现场清点宝数。
// 不按类型一刀切：巡检、补宝要求拍照只会让人随手拍张地板交差。
// 成本（配件 / 人工）完工时填，归属由后端判：代理运维的单记代理，其余记站点效益。
import * as React from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { woCompleteRules, type WorkOrder, type WorkOrderHandlePayload, type WorkOrderType, type WoFaultReason } from "@/lib/types";
import { WO_TYPE_LABEL } from "@/components/status";
import { FAULT_REASON_OPTIONS } from "./wo-meta";

/**
 * 完工表单（字段键 = 后端 HandleReq）。
 * @form POST /api/ops/work-orders/{woNo}/complete
 */
const completeFields = (type: WorkOrderType): FieldDef[] => [
  { key: "handleNote", label: "处理说明", type: "textarea", rows: 4, required: true, maxLength: 500, section: "现场处理",
    placeholder: "到场时间、排查过程、处理动作、复测结果" },
  ...(woCompleteRules(type).faultReason ? [{
    key: "faultReasonCode", label: "故障原因", type: "select" as const, required: true, section: "现场处理",
    options: [{ value: "", label: "请选择故障原因" }, ...FAULT_REASON_OPTIONS],
    help: "统计「这类设备最常坏在哪」的唯一依据，不要一律选「其他」",
  }] : []),
  ...(woCompleteRules(type).countedQty ? [{
    key: "countedQty", label: "现场清点宝数", type: "number" as const, required: true, min: 0, section: "现场处理",
    help: "与系统在柜数比对，不一致会挂资产差异",
  }] : []),
  ...(woCompleteRules(type).locationNo ? [{
    key: "locationNo", label: "装机点位号", section: "现场处理", placeholder: "扫点位码或手填，如 LOC1203",
    help: "装在哪个点位以现场扫码为准；留空则沿用工单上的点位",
  }] : []),
  { key: "fileNos", label: "现场照片", type: "file", fileCategory: "WO_PHOTO", maxFiles: 9,
    required: woCompleteRules(type).photos, section: "现场照片",
    help: woCompleteRules(type).photos
      ? `${WO_TYPE_LABEL[type] ?? type}单完工至少一张：处理后的设备全貌 + 更换部位特写`
      : "可选；有照片的单验收更快" },
  { key: "partChanged", label: "更换了配件", type: "switch", section: "成本" },
  { key: "partCost", label: "配件金额（AED）", type: "number", min: 0, section: "成本",
    disabledWhen: (v) => !v.partChanged, help: "未更换配件时不计" },
  { key: "laborCost", label: "人工金额（AED）", type: "number", min: 0, section: "成本",
    help: "代理运维的单记入代理成本，其余记入站点效益" },
];

/**
 * 提交处理进展（不改状态，可多次）。
 * @form POST /api/ops/work-orders/{woNo}/handle
 */
const processFields: FieldDef[] = [
  { key: "handleNote", label: "处理进展", type: "textarea", rows: 4, required: true, maxLength: 500,
    placeholder: "到场情况、已做的排查、下一步" },
  { key: "fileNos", label: "现场照片（可选）", type: "file", fileCategory: "WO_PHOTO", maxFiles: 9 },
  { key: "partChanged", label: "更换了配件", type: "switch" },
];

const numOrNull = (v: unknown) => (v === "" || v == null || Number.isNaN(Number(v)) ? null : Number(v));
const strOrNull = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : null);

/** 表单值 → 后端 HandleReq。空串一律转 null，不往后端送「看起来有值」的空串。 */
export function toHandlePayload(v: Record<string, unknown>): WorkOrderHandlePayload {
  return {
    handleNote: String(v.handleNote ?? "").trim(),
    faultReasonCode: (strOrNull(v.faultReasonCode) as WoFaultReason | null),
    fileNos: Array.isArray(v.fileNos) ? (v.fileNos as string[]) : [],
    partChanged: !!v.partChanged,
    partCost: v.partChanged ? numOrNull(v.partCost) : null,
    laborCost: numOrNull(v.laborCost),
    countedQty: numOrNull(v.countedQty),
    locationNo: strOrNull(v.locationNo),
  };
}

export function WoHandleDrawer({
  target, onClose, onDone,
}: {
  target: { wo: WorkOrder; action: "process" | "complete" } | null;
  onClose: () => void;
  onDone: (msg: string) => void;
}) {
  const [v, setV] = React.useState<Record<string, unknown>>({});
  React.useEffect(() => {
    if (target) setV({ handleNote: "", fileNos: [], partChanged: false, faultReasonCode: "", partCost: "", laborCost: "", countedQty: "", locationNo: "" });
  }, [target]);

  const run = useMutation({
    mutationFn: () => {
      const p = toHandlePayload(v);
      return target!.action === "complete"
        ? api.completeWorkOrder(target!.wo.woNo, p)
        : api.processWorkOrder(target!.wo.woNo, p);
    },
    onSuccess: (r) => onDone(target?.action === "complete"
      ? (r.status === "CLOSED" ? "已完工：关联告警全部恢复，系统已自动验收关单" : "已完工，等待验收")
      : "处理进展已提交"),
  });

  const fields = React.useMemo(
    () => (target?.action === "complete" ? completeFields(target.wo.type) : processFields),
    [target],
  );

  return (
    <FormDrawer
      open={!!target}
      onOpenChange={(o) => !o && onClose()}
      titleNew={`${target?.action === "complete" ? "完工" : "提交处理"} ${target?.wo.woNo ?? ""}`}
      titleEdit=""
      isEdit={false}
      width="w-[520px]"
      fields={fields}
      value={v}
      onChange={setV}
      onSubmit={() => run.mutate()}
      submitting={run.isPending}
    />
  );
}
