// FormDrawer 字段校验的纯函数实现（不依赖 React/DOM，便于单测）。
// 校验顺序固定：required → min/max → maxLength → pattern，先命中先返回（每字段只报一条）。
// 文案全部走 i18n key（form.*），由调用方传入 t。

export type FieldType =
  | "text" | "number" | "select" | "switch" | "password"
  | "textarea" | "date" | "multiselect";

export type FormValues = Record<string, unknown>;

/** 参与校验的字段属性子集；UI 专属属性（options/placeholder/section…）见 FieldDef。 */
export type ValidatableField = {
  key: string;
  label: string;
  type?: FieldType;
  /** 必填。switch 型永远不判必填（false 是合法值）。 */
  required?: boolean;
  /** number 最小值 */
  min?: number;
  /** number 最大值 */
  max?: number;
  /** 字符串长度上限 */
  maxLength?: number;
  /** 正则校验；re 为字符串，内部 new RegExp。msg 可为字面文案，也可为 i18n key。 */
  pattern?: { re: string; msg: string };
  /** 编辑既有记录时只读 */
  readOnlyOnEdit?: boolean;
  /** 联动禁用 */
  disabledWhen?: (v: FormValues) => boolean;
};

export type TranslateFn = (key: string, params?: Record<string, string | number>) => string;

/** 空值判定：null/undefined/空串（trim 后）/空数组为空；0 与 false 不算空。 */
export function isEmptyValue(v: unknown): boolean {
  if (v == null) return true;
  if (typeof v === "string") return v.trim() === "";
  if (Array.isArray(v)) return v.length === 0;
  return false;
}

/** 字段是否处于禁用态（禁用字段不参与校验，避免拿不可编辑的值卡住提交）。 */
export function isFieldDisabled(f: ValidatableField, values: FormValues, isEdit: boolean): boolean {
  if (f.readOnlyOnEdit && isEdit) return true;
  if (f.disabledWhen) {
    try {
      return !!f.disabledWhen(values);
    } catch {
      return false;
    }
  }
  return false;
}

/** 校验单字段，返回错误文案；无错返回 null。 */
export function validateField(
  f: ValidatableField, values: FormValues, t: TranslateFn, isEdit = false,
): string | null {
  if (isFieldDisabled(f, values, isEdit)) return null;
  const v = values[f.key];
  const label = f.label;

  // 1) required
  if (f.required && f.type !== "switch" && isEmptyValue(v)) {
    return t("form.required", { label });
  }
  // 空值（且非必填）不再往下走：min/maxLength/pattern 对空值无意义。
  if (isEmptyValue(v)) return null;

  // 2) min / max（按数字比较）
  if (f.min != null || f.max != null) {
    const n = typeof v === "number" ? v : Number(String(v));
    if (Number.isNaN(n)) return t("form.numberInvalid", { label });
    if (f.min != null && n < f.min) return t("form.minValue", { label, min: f.min });
    if (f.max != null && n > f.max) return t("form.maxValue", { label, max: f.max });
  } else if (f.type === "number" && Number.isNaN(Number(String(v)))) {
    return t("form.numberInvalid", { label });
  }

  // 3) maxLength
  if (f.maxLength != null && valueLength(v) > f.maxLength) {
    return t("form.maxLength", { label, max: f.maxLength });
  }

  // 4) pattern
  if (f.pattern) {
    let re: RegExp | null = null;
    try {
      re = new RegExp(f.pattern.re);
    } catch {
      re = null; // 正则本身写错时不误伤用户输入
    }
    if (re && !re.test(String(v))) return t(f.pattern.msg);
  }
  return null;
}

function valueLength(v: unknown): number {
  if (Array.isArray(v)) return v.length;
  return String(v).length;
}

/** 校验全部字段，返回 { key: 错误文案 }；无错则为空对象。 */
export function validateAll(
  fields: ValidatableField[], values: FormValues, t: TranslateFn, isEdit = false,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of fields) {
    const e = validateField(f, values, t, isEdit);
    if (e) out[f.key] = e;
  }
  return out;
}

/** multiselect 的 csv 互转：对外逗号字符串，UI 内部数组。 */
export function csvToArray(v: unknown): string[] {
  if (Array.isArray(v)) return v.map(String);
  if (typeof v === "string") return v.split(",").map((s) => s.trim()).filter(Boolean);
  return [];
}

export function arrayToCsv(a: string[]): string {
  return a.join(",");
}

/** 把字段按「相邻且同名 section」切段；无 section 的字段自成一段（section 为 undefined）。 */
export function groupBySection<T extends { section?: string }>(fields: T[]): { section?: string; fields: T[] }[] {
  const segs: { section?: string; fields: T[] }[] = [];
  for (const f of fields) {
    const last = segs[segs.length - 1];
    if (last && last.section === f.section) last.fields.push(f);
    else segs.push({ section: f.section, fields: [f] });
  }
  return segs;
}
