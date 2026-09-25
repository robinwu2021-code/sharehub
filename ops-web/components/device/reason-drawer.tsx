"use client";

// 「写原因」抽屉：标故障 / 撤机 / 报废 / 解除保护共用。
//
// 这些动作后端都**原因必填**（requireReason）。原因不是走流程的摆设：
// 三个月后有人问「这台柜子为什么报废了」「这条停借是谁、为什么解的」，能回答的只有这一句。
// 打开抽屉本身无副作用，点「保存」才发请求 —— 抽屉就是这类动作的确认步骤（R4）。
import { useEffect, useState } from "react";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";

/**
 * @form POST /api/ops/devices/{deviceNo}/mark-fault
 * @form POST /api/ops/devices/{deviceNo}/undeploy
 * @form POST /api/ops/devices/{deviceNo}/retire
 * @form POST /api/ops/devices/protections/{protectionNo}/release
 */
const reasonFields = (help: string): FieldDef[] => [
  { key: "reason", label: "原因", type: "textarea", required: true, maxLength: 512, rows: 4, help },
];

export interface ReasonRequest {
  title: string;
  /** 字段下方的说明：这一步会带来什么后果。 */
  help: string;
  onSubmit: (reason: string) => Promise<unknown>;
}

export function ReasonDrawer({ req, onClose }: { req: ReasonRequest | null; onClose: () => void }) {
  const [form, setForm] = useState<Record<string, unknown>>({});
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (req) setForm({}); }, [req]);

  const submit = async () => {
    if (!req) return;
    setBusy(true);
    try {
      await req.onSubmit(String(form.reason ?? "").trim());
      onClose();
    } catch {
      // 错误由全局 MutationCache / 调用方 toast 处理；抽屉留着让人改完再提交
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormDrawer
      open={!!req}
      onOpenChange={(o) => !o && onClose()}
      titleNew={req?.title ?? ""}
      titleEdit={req?.title ?? ""}
      isEdit={false}
      fields={reasonFields(req?.help ?? "")}
      value={form}
      onChange={setForm}
      onSubmit={() => void submit()}
      submitting={busy}
    />
  );
}
