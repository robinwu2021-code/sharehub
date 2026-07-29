"use client";

// 配置化编辑抽屉：给字段定义即得「新增/编辑」表单（右侧抽屉），统一所有列表页的编辑体验。
// 用法：<FormDrawer open={!!form} onOpenChange fields={FIELDS} value={form} onChange={setForm} onSubmit=.../>
import * as React from "react";
import { Drawer, Field } from "./drawer";
import { Input, Select } from "./input";
import { Button } from "./button";
import { useI18n } from "@/lib/i18n";

export type FieldDef = {
  key: string;
  label: string;
  /** password：密钥类字段，输入掩码显示（值不回显明文样式，仅用于配置类抽屉）。 */
  type?: "text" | "number" | "select" | "switch" | "password";
  options?: { value: string; label: string }[];
  placeholder?: string;
  /** 编辑既有记录时只读（如业务主键）。 */
  readOnlyOnEdit?: boolean;
};

type FormValue = Record<string, unknown>;

function InputForField({
  f, value, onChange, isEdit,
}: { f: FieldDef; value: FormValue; onChange: (v: FormValue) => void; isEdit: boolean }) {
  const set = (v: unknown) => onChange({ ...value, [f.key]: v });
  const cur = value[f.key];
  const disabled = !!(f.readOnlyOnEdit && isEdit);

  if (f.type === "select") {
    return (
      <Select className="w-full" value={(cur as string) ?? ""} disabled={disabled} onChange={(e) => set(e.target.value)}>
        {(f.options ?? []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </Select>
    );
  }
  if (f.type === "switch") {
    return (
      <button
        type="button"
        role="switch"
        aria-checked={!!cur}
        onClick={() => set(!cur)}
        className={`h-6 w-11 rounded-full transition-colors ${cur ? "bg-primary" : "bg-secondary"}`}
      >
        <span className={`block size-5 rounded-full bg-card shadow-[var(--card-shadow)] transition-transform ${cur ? "translate-x-6 rtl:-translate-x-6" : "translate-x-0.5"}`} />
      </button>
    );
  }
  return (
    <Input
      type={f.type === "number" ? "number" : f.type === "password" ? "password" : "text"}
      value={(cur as string | number) ?? ""}
      disabled={disabled}
      placeholder={f.placeholder}
      onChange={(e) => set(f.type === "number" ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)}
    />
  );
}

export function FormDrawer({
  open, onOpenChange, titleNew, titleEdit, isEdit, fields, value, onChange, onSubmit, submitting,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  titleNew: string;
  titleEdit: string;
  isEdit: boolean;
  fields: FieldDef[];
  value: FormValue;
  onChange: (v: FormValue) => void;
  onSubmit: () => void;
  submitting?: boolean;
}) {
  const { t } = useI18n();
  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={isEdit ? titleEdit : titleNew}
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={submitting} onClick={onSubmit}>{t("common.save")}</Button>
        </>
      }
    >
      {fields.map((f) => (
        <Field key={f.key} label={f.label}>
          <InputForField f={f} value={value} onChange={onChange} isEdit={isEdit} />
        </Field>
      ))}
    </Drawer>
  );
}
