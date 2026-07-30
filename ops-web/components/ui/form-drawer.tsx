"use client";

// 配置化编辑抽屉：给字段定义即得「新增/编辑」表单（右侧抽屉），统一所有列表页的编辑体验。
// 用法：<FormDrawer open={!!form} onOpenChange fields={FIELDS} value={form} onChange={setForm} onSubmit=.../>
//
// 能力：
// - 字段类型：text/number/select/switch/password/textarea/date/multiselect
// - 校验：blur 校验单字段 + 提交校验全部；错误在字段下方红字，输入框描边转 destructive；有错时保存按钮禁用
// - 分区：相邻同名 section 合成一段，段首渲染小标题（样式对齐 secondary-nav 的分组小标题）
// - 联动：disabledWhen(values) 为 true 时禁用该字段并清空其值（不提交隐藏字段的脏数据）
import * as React from "react";
import { Drawer } from "./drawer";
import { Input, Select } from "./input";
import { DateInput } from "./date-input";
import { MultiSelect } from "./multi-select";
import { Button } from "./button";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";
import {
  type FieldType, type FormValues, type ValidatableField,
  validateAll, isFieldDisabled, isEmptyValue, csvToArray, arrayToCsv, groupBySection,
} from "@/lib/form-validate";

export type { FieldType } from "@/lib/form-validate";

export type FieldDef = ValidatableField & {
  key: string;
  label: string;
  /** password：密钥类字段，输入掩码显示（值不回显明文样式，仅用于配置类抽屉）。 */
  type?: FieldType;
  options?: { value: string; label: string }[];
  placeholder?: string;
  /** 编辑既有记录时只读（如业务主键）。 */
  readOnlyOnEdit?: boolean;
  /** 分区标题：连续同名 section 的字段归为一组，段首渲染小标题。 */
  section?: string;
  /** textarea 行数，默认 3。 */
  rows?: number;
  /** 仅 multiselect：值以逗号字符串存储（如 "AE,SA"），UI 内部转数组。 */
  csv?: boolean;
  /** 字段下方灰色说明。 */
  help?: string;
};

type FormValue = FormValues;

/** 输入框在错误态下的描边（扁平色块 + ring，不引描边分割线）。 */
const ERR_RING = "ring-2 ring-destructive";

function InputForField({
  f, value, onChange, isEdit, invalid, onBlur,
}: {
  f: FieldDef;
  value: FormValue;
  onChange: (v: FormValue) => void;
  isEdit: boolean;
  invalid?: boolean;
  onBlur?: () => void;
}) {
  const set = (v: unknown) => onChange({ ...value, [f.key]: v });
  const cur = value[f.key];
  const disabled = isFieldDisabled(f, value, isEdit);

  if (f.type === "select") {
    return (
      <Select
        className={cn("w-full", invalid && ERR_RING)}
        value={(cur as string) ?? ""}
        disabled={disabled}
        onBlur={onBlur}
        onChange={(e) => set(e.target.value)}
      >
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
        disabled={disabled}
        onClick={() => set(!cur)}
        className={cn(
          "h-6 w-11 rounded-chip transition-colors disabled:opacity-50",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
          cur ? "bg-primary" : "bg-secondary",
        )}
      >
        <span className={cn("block size-5 rounded-chip bg-card shadow-[var(--card-shadow)] transition-transform", cur ? "translate-x-6 rtl:-translate-x-6" : "translate-x-0.5")} />
      </button>
    );
  }
  if (f.type === "textarea") {
    return (
      <textarea
        rows={f.rows ?? 3}
        value={(cur as string) ?? ""}
        disabled={disabled}
        placeholder={f.placeholder}
        onBlur={onBlur}
        onChange={(e) => set(e.target.value)}
        className={cn(
          "flex w-full resize-y rounded-field bg-secondary px-3.5 py-2 text-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)] disabled:opacity-50",
          invalid && ERR_RING,
        )}
      />
    );
  }
  if (f.type === "date") {
    return (
      <DateInput
        className={cn(invalid && ERR_RING)}
        value={(cur as string) ?? ""}
        disabled={disabled}
        onBlur={onBlur}
        onChange={(e) => set(e.target.value)}
      />
    );
  }
  if (f.type === "multiselect") {
    return (
      <MultiSelect
        value={csvToArray(cur)}
        options={f.options ?? []}
        disabled={disabled}
        placeholder={f.placeholder}
        invalid={invalid}
        onBlur={onBlur}
        onChange={(arr) => set(f.csv ? arrayToCsv(arr) : arr)}
      />
    );
  }
  return (
    <Input
      className={cn(invalid && ERR_RING)}
      type={f.type === "number" ? "number" : f.type === "password" ? "password" : "text"}
      value={(cur as string | number) ?? ""}
      disabled={disabled}
      placeholder={f.placeholder}
      onBlur={onBlur}
      onChange={(e) => set(f.type === "number" ? (e.target.value === "" ? "" : Number(e.target.value)) : e.target.value)}
    />
  );
}

/** 单个字段行：标签（必填星号）+ 控件 + 错误/说明/字数计数。 */
function FieldRow({
  f, value, onChange, isEdit, error, onBlur,
}: {
  f: FieldDef;
  value: FormValue;
  onChange: (v: FormValue) => void;
  isEdit: boolean;
  error?: string;
  onBlur: () => void;
}) {
  const cur = value[f.key];
  const len = f.maxLength != null && cur != null ? String(cur).length : 0;
  const over = f.maxLength != null && len > f.maxLength;
  return (
    <div className="mb-4">
      <div className="mb-1 flex items-center gap-1 text-xs text-muted-foreground">
        <span>{f.label}</span>
        {f.required && <span className="text-destructive">*</span>}
        {f.maxLength != null && (
          <span className={cn("ms-auto tabular-nums", over ? "text-destructive" : "text-muted-foreground/60")}>
            {len}/{f.maxLength}
          </span>
        )}
      </div>
      <div className="text-sm">
        <InputForField f={f} value={value} onChange={onChange} isEdit={isEdit} invalid={!!error} onBlur={onBlur} />
      </div>
      {error && <div className="mt-1 text-xs text-destructive">{error}</div>}
      {!error && f.help && <div className="mt-1 text-xs text-muted-foreground/70">{f.help}</div>}
    </div>
  );
}

export function FormDrawer({
  open, onOpenChange, titleNew, titleEdit, isEdit, fields, value, onChange, onSubmit, submitting, width,
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
  width?: string;
}) {
  const { t } = useI18n();
  const [touched, setTouched] = React.useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = React.useState(false);

  // 每次开合重置「已触碰/已提交」，避免上一条记录的错误残留。
  React.useEffect(() => {
    if (!open) { setTouched({}); setSubmitted(false); }
  }, [open]);

  // 联动禁用：被 disabledWhen 关掉的字段清空其值，防止提交隐藏字段的脏数据。
  React.useEffect(() => {
    if (!open) return;
    const clear: string[] = [];
    for (const f of fields) {
      if (!f.disabledWhen) continue;
      if (isFieldDisabled(f, value, isEdit) && !isEmptyValue(value[f.key])) clear.push(f.key);
    }
    if (clear.length) {
      const next = { ...value };
      for (const k of clear) next[k] = emptyValueFor(fields, k);
      onChange(next);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, value, fields, isEdit]);

  const errors = React.useMemo(() => validateAll(fields, value, t, isEdit), [fields, value, t, isEdit]);
  const hasError = Object.keys(errors).length > 0;
  const segments = React.useMemo(() => groupBySection(fields), [fields]);

  const submit = () => {
    setSubmitted(true);
    if (Object.keys(validateAll(fields, value, t, isEdit)).length > 0) return;
    onSubmit();
  };

  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={isEdit ? titleEdit : titleNew}
      width={width}
      footer={
        <>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          {/*
            保存按钮**不因校验错误而禁用**：新建时字段全空必然有错，若禁用则用户
            看到灰按钮却不知道为什么，且 submit() 里的校验分支永远执行不到。
            改为「可点 → 点击后标记 submitted → 红字暴露全部错误 → 不提交」。
            仅 submitting 时禁用（防重复提交）。
          */}
          <Button disabled={submitting} onClick={submit} aria-invalid={hasError || undefined}>{t("common.save")}</Button>
        </>
      }
    >
      {segments.map((seg, si) => (
        <div key={seg.section ?? `seg${si}`} className={cn(seg.section && si > 0 && "mt-1")}>
          {seg.section && (
            <div className="mb-2 pt-1 text-[12px] font-semibold uppercase tracking-wide text-muted-foreground">
              {seg.section}
            </div>
          )}
          {seg.fields.map((f) => (
            <FieldRow
              key={f.key}
              f={f}
              value={value}
              onChange={onChange}
              isEdit={isEdit}
              error={(touched[f.key] || submitted) ? errors[f.key] : undefined}
              onBlur={() => setTouched((s) => (s[f.key] ? s : { ...s, [f.key]: true }))}
            />
          ))}
        </div>
      ))}
    </Drawer>
  );
}

/** 清空联动禁用字段时按类型给「空值」：multiselect(非 csv) 给 []，其余给 ""。 */
function emptyValueFor(fields: FieldDef[], key: string): unknown {
  const f = fields.find((x) => x.key === key);
  if (f?.type === "multiselect" && !f.csv) return [];
  if (f?.type === "switch") return false;
  return "";
}
