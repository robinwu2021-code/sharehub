// 前端 CSV 导入（G2，TDD §10.2）。与 export-csv.ts 对称：静态导出 SPA，解析在浏览器里做。
//
// 设计要点（都是踩过的坑）：
// 1. **先全量校验、再整批落库**——规格明写「不允许导一半失败」。故 parse 与 commit 分离：
//    本模块只产出「解析结果 + 逐行错误清单」，一条不过就不该调导入接口。
// 2. 错误必须**定位到第几行、哪个字段**。只说「格式错误」等于让人拿 200 行 CSV 大海捞针。
//    行号用**文件行号**（含表头，从 1 起），与用户在 Excel 里看到的行号对齐。
// 3. 解析要认 Excel 导出的现实：UTF-8 BOM、CRLF、字段内含逗号/换行的双引号包裹、"" 转义。

/** 一列的导入定义。 */
export interface ImportColumn<T> {
  /** CSV 表头文字（中文），必须与导出模板一致 */
  header: string;
  /** 落到对象上的字段名 */
  key: keyof T & string;
  /** 必填：空值直接判错 */
  required?: boolean;
  /** 原始串 → 值。抛错等价于返回校验失败（消息取 error.message） */
  parse?: (raw: string) => unknown;
  /** 额外校验：返回错误消息则判错，返回 null 表示通过 */
  validate?: (value: unknown, raw: string) => string | null;
  /** 本列在整个文件内必须唯一（如机柜号、SN） */
  unique?: boolean;
}

export interface RowError {
  /** 文件行号（含表头，从 1 起），与 Excel 行号对齐 */
  line: number;
  /** 出错的列名；整表级错误为空串 */
  header: string;
  message: string;
}

export interface ImportResult<T> {
  /** 校验通过的行（有任何错误时也会返回已解析的部分，供预览用） */
  rows: T[];
  errors: RowError[];
  /** 文件里实际出现的表头，用于提示「缺了哪列」 */
  headers: string[];
}

/** 剥掉 UTF-8 BOM——Excel 导出的 CSV 必带，不剥掉第一个表头会变成 `﻿机柜号` 而匹配不上。 */
const stripBom = (s: string) => (s.charCodeAt(0) === 0xfeff ? s.slice(1) : s);

/**
 * CSV → 二维数组。手写而非引第三方：规则就这几条，引个库反而多一份供应链风险。
 * 支持双引号包裹、内部 `""` 转义、字段内换行、CRLF/LF 混用。
 */
export function parseCsv(text: string): string[][] {
  const src = stripBom(text);
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;

  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (quoted) {
      if (c === '"') {
        if (src[i + 1] === '"') { cell += '"'; i++; } // "" → 字面量引号
        else quoted = false;
      } else cell += c;
      continue;
    }
    if (c === '"') { quoted = true; continue; }
    if (c === ",") { row.push(cell); cell = ""; continue; }
    if (c === "\r") continue; // CRLF 的 \r 直接吞掉
    if (c === "\n") { row.push(cell); rows.push(row); row = []; cell = ""; continue; }
    cell += c;
  }
  row.push(cell);
  rows.push(row);

  // 末尾空行（文件以换行结束）不算数据
  return rows.filter((r) => !(r.length === 1 && r[0].trim() === ""));
}

/**
 * 解析并逐行校验。
 *
 * 整表级错误（缺必填列）会直接返回，不再逐行报错——200 行同一个错刷屏没有信息量。
 */
export function parseImport<T>(text: string, columns: ImportColumn<T>[]): ImportResult<T> {
  const table = parseCsv(text);
  if (table.length === 0) {
    return { rows: [], errors: [{ line: 1, header: "", message: "文件为空" }], headers: [] };
  }

  const headers = table[0].map((h) => h.trim());
  const missing = columns.filter((c) => c.required && !headers.includes(c.header));
  if (missing.length > 0) {
    return {
      rows: [], headers,
      errors: [{
        line: 1, header: "",
        message: `表头缺少必填列：${missing.map((c) => c.header).join("、")}。请下载模板后按模板填写`,
      }],
    };
  }
  if (table.length === 1) {
    return { rows: [], headers, errors: [{ line: 1, header: "", message: "只有表头，没有数据行" }] };
  }

  const idx = new Map(headers.map((h, i) => [h, i]));
  const rows: T[] = [];
  const errors: RowError[] = [];
  const seen = new Map<string, Map<string, number>>(); // 列 → 值 → 首次出现的行号

  table.slice(1).forEach((cells, i) => {
    const line = i + 2; // +1 表头 +1 从 1 起
    if (cells.every((c) => c.trim() === "")) return; // 整行空白：跳过而非报错
    const obj = {} as Record<string, unknown>;
    let rowOk = true;

    for (const col of columns) {
      const at = idx.get(col.header);
      const raw = (at === undefined ? "" : cells[at] ?? "").trim();

      if (raw === "") {
        if (col.required) { errors.push({ line, header: col.header, message: "不能为空" }); rowOk = false; }
        continue;
      }

      let value: unknown = raw;
      if (col.parse) {
        try { value = col.parse(raw); }
        catch (e) {
          errors.push({ line, header: col.header, message: e instanceof Error ? e.message : "格式不正确" });
          rowOk = false;
          continue;
        }
      }
      const msg = col.validate?.(value, raw);
      if (msg) { errors.push({ line, header: col.header, message: msg }); rowOk = false; continue; }

      if (col.unique) {
        const bucket = seen.get(col.header) ?? new Map<string, number>();
        const first = bucket.get(raw);
        if (first !== undefined) {
          errors.push({ line, header: col.header, message: `与第 ${first} 行重复（同一文件内必须唯一）` });
          rowOk = false;
        } else bucket.set(raw, line);
        seen.set(col.header, bucket);
      }

      obj[col.key] = value;
    }

    if (rowOk) rows.push(obj as T);
  });

  return { rows, errors, headers };
}

/** 生成导入模板 CSV 文本（表头 + 一行示例），供抽屉里「下载模板」用。 */
export function templateCsv<T>(columns: ImportColumn<T>[], sample: Record<string, string> = {}): string {
  const esc = (s: string) => (/[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  return "﻿" + [
    columns.map((c) => esc(c.header)).join(","),
    columns.map((c) => esc(sample[c.header] ?? "")).join(","),
  ].join("\r\n");
}
