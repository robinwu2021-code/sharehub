"use client";

// 运营台「发起审批」（OpsKit 38983255）：选一个审批模板，按模板的发起表单填写，发起即进入审批。
// 只列启用的模板；表单字段由模板定义（TEXT / TEXTAREA / NUMBER / DATE / SELECT），前端不内置业务字段。
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { isValidationError } from "@/lib/flow/errors";
import { useFlowT } from "@/lib/flow/i18n";
import type { I18nText, TemplateFieldType, TemplateFormField } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { FormDrawer, type FieldDef, type FieldType } from "@/components/ui/form-drawer";

/** 表单里模板字段的键前缀：与固定项「模板」隔开 */
const KEY = "f.";
const FORM_TYPE: Record<TemplateFieldType, FieldType> = {
  TEXT: "text", TEXTAREA: "textarea", NUMBER: "number", DATE: "date", SELECT: "select",
};

/**
 * @form-none 发起审批提交到 OpsKit（opskit-svc 的 POST /workspaces/{ws}/instances），
 * 不是 ShareHub 后端，contract.json 管不到；字段由所选审批模板的发起表单定义，经 lib/flow/client 转发。
 */
export function StartApprovalDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  const { t, tc, locale } = useFlowT("approvals");
  const router = useRouter();
  const qc = useQueryClient();
  const [form, setForm] = useState<Record<string, unknown>>({ tpl: "" });
  const code = String(form.tpl ?? "");
  const pick = (n: I18nText) => n[locale] || n.en || n.zh || n.ar || "";

  const templates = useQuery({ queryKey: [...FLOW_KEY, "templates"], queryFn: flow.templates, enabled: open });
  const tpl = useQuery({ queryKey: [...FLOW_KEY, "template", code], queryFn: () => flow.template(code), enabled: open && !!code });
  const tplFields: TemplateFormField[] = tpl.data?.fields ?? [];

  const fields: FieldDef[] = [
    { key: "tpl", label: t("start.template"), type: "select", required: true,
      help: code && tpl.data && !tplFields.length ? t("start.noForm") : undefined,
      options: [{ value: "", label: t("start.pick") },
        ...(templates.data ?? []).filter((x) => x.enabled).map((x) => ({ value: x.code, label: x.name }))] },
    ...tplFields.map((f): FieldDef => ({
      key: KEY + f.code, label: pick(f.name), type: FORM_TYPE[f.type], required: f.required,
      ...(f.type === "SELECT" ? { options: [{ value: "", label: t("start.pick") }, ...(f.options ?? []).map((o) => ({ value: o.code, label: pick(o.name) }))] } : {}),
      ...(f.type === "TEXT" ? { maxLength: 200 } : f.type === "TEXTAREA" ? { maxLength: 2000 } : {}),
    })),
  ];

  /** 换模板：旧模板的字段值作废（留着会提交别的模板的字段，被服务按「未知字段」拒） */
  const onChange = (next: Record<string, unknown>) => {
    if (next.tpl === form.tpl) return setForm(next);
    setForm({ tpl: next.tpl });
  };

  const start = useMutation({
    mutationFn: () => {
      const values: Record<string, unknown> = {};
      for (const f of tplFields) {
        const v = form[KEY + f.code];
        if (v == null || v === "") continue;
        values[f.code] = f.type === "NUMBER" ? Number(v) : typeof v === "string" ? v.trim() : v;
      }
      return flow.startApproval(code, values);
    },
    onSuccess: (r) => {
      notify.success(r.approvalNo ? t("start.done", { no: r.approvalNo }) : t("start.doneNoApproval", { no: r.instanceNo }));
      qc.invalidateQueries({ queryKey: FLOW_KEY });
      onOpenChange(false);
      setForm({ tpl: "" });
      if (r.approvalNo) router.push(`/flow/approvals/detail/?no=${encodeURIComponent(r.approvalNo)}`);
    },
    // 422 逐条说「哪个字段：什么问题」—— 路径 fields.<代号> 反查表单标签
    onError: (e: Error) => {
      if (!isValidationError(e)) return notify.error(e.message);
      const label = (path: string) => {
        const f = path.startsWith("fields.") ? tplFields.find((x) => x.code === path.slice(7)) : undefined;
        return f ? pick(f.name) : path;
      };
      notify.error(`${e.message}${tc("colon")}${e.errors.map((x) => `${label(x.path)}${tc("colon")}${x.error}`).join(tc("listSep"))}`);
    },
  });

  return (
    <FormDrawer
      open={open}
      onOpenChange={onOpenChange}
      titleNew={t("start.title")}
      titleEdit=""
      isEdit={false}
      fields={fields}
      value={form}
      onChange={onChange}
      onSubmit={() => start.mutate()}
      submitting={start.isPending}
    />
  );
}
