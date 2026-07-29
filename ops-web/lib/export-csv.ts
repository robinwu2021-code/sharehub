// 前端 CSV 导出（静态导出 SPA，无后端导出接口）。
// 关键点：必须带 UTF-8 BOM，否则 Excel 打开中文乱码——这是本模块存在的主要理由之一。

export interface CsvColumn<T> {
  header: string;
  value: (row: T) => string | number | null | undefined;
}

/** UTF-8 BOM。Excel 靠它识别编码。 */
export const CSV_BOM = "﻿";

/** 单元格转义：含逗号/引号/换行(含 \r)时用双引号包裹，内部引号翻倍。null/undefined → 空串。 */
export function escapeCsvCell(v: string | number | null | undefined): string {
  if (v === null || v === undefined) return "";
  const s = String(v);
  if (s === "") return "";
  if (/[",\r\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

/** 纯函数：生成带 BOM 的 CSV 文本（CRLF 行尾，Excel 友好）。 */
export function toCsv<T>(columns: CsvColumn<T>[], rows: T[]): string {
  const lines = [
    columns.map((c) => escapeCsvCell(c.header)).join(","),
    ...rows.map((r) => columns.map((c) => escapeCsvCell(c.value(r))).join(",")),
  ];
  return CSV_BOM + lines.join("\r\n");
}

/** 文件名加日期后缀：`发送记录` → `发送记录-2026-07-29.csv`；已带 .csv 的会先剥掉。 */
export function csvFilename(name: string, date: Date = new Date()): string {
  const base = name.replace(/\.csv$/i, "");
  const p = (n: number) => String(n).padStart(2, "0");
  const suffix = `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
  return `${base}-${suffix}.csv`;
}

/** 浏览器端触发下载。页面均为 client component，可直接调用。 */
export function exportCsv<T>(
  filename: string,
  columns: CsvColumn<T>[],
  rows: T[],
  date?: Date,
): void {
  const text = toCsv(columns, rows);
  if (typeof document === "undefined" || typeof URL?.createObjectURL !== "function") return;
  const blob = new Blob([text], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = csvFilename(filename, date);
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
