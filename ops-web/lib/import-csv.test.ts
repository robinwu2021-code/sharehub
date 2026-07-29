// CSV 导入解析与校验单测（G2）。
// 重点钉住两条规格要求：① 错误必须定位到「第几行哪个字段」 ② 有错就不产出该行（先校验再落库）。
import { describe, it, expect } from "vitest";
import { parseCsv, parseImport, templateCsv, type ImportColumn } from "./import-csv";

interface Row { cabinetNo: string; sn: string; slotTotal: number; model?: string }

const COLS: ImportColumn<Row>[] = [
  { header: "机柜号", key: "cabinetNo", required: true, unique: true,
    validate: (v) => (/^CAB\d+$/.test(String(v)) ? null : "格式应为 CAB + 数字，如 CAB1000") },
  { header: "SN", key: "sn", required: true, unique: true },
  { header: "仓位数", key: "slotTotal", required: true,
    parse: (raw) => { const n = Number(raw); if (!Number.isInteger(n)) throw new Error("必须是整数"); return n; },
    validate: (v) => ((v as number) >= 1 && (v as number) <= 48 ? null : "应在 1~48 之间") },
  { header: "型号", key: "model" },
];

describe("parseCsv", () => {
  it("剥掉 UTF-8 BOM（Excel 导出必带，不剥第一个表头就匹配不上）", () => {
    expect(parseCsv("﻿a,b\n1,2")).toEqual([["a", "b"], ["1", "2"]]);
  });

  it("认 CRLF、双引号包裹、字段内逗号与换行、\"\" 转义", () => {
    const text = 'a,b\r\n"含,逗号","含""引号"\r\n"跨\n行",x\r\n';
    expect(parseCsv(text)).toEqual([
      ["a", "b"],
      ["含,逗号", '含"引号'],
      ["跨\n行", "x"],
    ]);
  });

  it("忽略文件末尾的空行", () => {
    expect(parseCsv("a\n1\n\n")).toEqual([["a"], ["1"]]);
  });
});

describe("parseImport", () => {
  it("正常文件全部解析成功，数值列按 parse 转成 number", () => {
    const r = parseImport<Row>("机柜号,SN,仓位数,型号\nCAB1000,SN001,8,X6\nCAB1001,SN002,12,S8\n", COLS);
    expect(r.errors).toEqual([]);
    expect(r.rows).toEqual([
      { cabinetNo: "CAB1000", sn: "SN001", slotTotal: 8, model: "X6" },
      { cabinetNo: "CAB1001", sn: "SN002", slotTotal: 12, model: "S8" },
    ]);
  });

  it("缺必填列时整表报错，不逐行刷屏", () => {
    const r = parseImport<Row>("机柜号,型号\nCAB1000,X6\n", COLS);
    expect(r.rows).toEqual([]);
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0].message).toContain("SN");
    expect(r.errors[0].message).toContain("仓位数");
  });

  it("错误定位到文件行号（与 Excel 行号一致）与具体字段", () => {
    const r = parseImport<Row>("机柜号,SN,仓位数\nCAB1000,SN001,8\nX1,SN002,8\nCAB1002,,99\n", COLS);
    expect(r.errors).toEqual([
      { line: 3, header: "机柜号", message: "格式应为 CAB + 数字，如 CAB1000" },
      { line: 4, header: "SN", message: "不能为空" },
      { line: 4, header: "仓位数", message: "应在 1~48 之间" },
    ]);
    // 有错的行不产出——先校验再落库，绝不放半截数据进去
    expect(r.rows).toEqual([{ cabinetNo: "CAB1000", sn: "SN001", slotTotal: 8 }]);
  });

  it("unique 列在文件内重复时指出与第几行撞了", () => {
    const r = parseImport<Row>("机柜号,SN,仓位数\nCAB1000,SN001,8\nCAB1000,SN002,8\n", COLS);
    expect(r.errors).toEqual([
      { line: 3, header: "机柜号", message: "与第 2 行重复（同一文件内必须唯一）" },
    ]);
    expect(r.rows).toHaveLength(1);
  });

  it("parse 抛错时消息透出到该行该列", () => {
    const r = parseImport<Row>("机柜号,SN,仓位数\nCAB1000,SN001,八\n", COLS);
    expect(r.errors).toEqual([{ line: 2, header: "仓位数", message: "必须是整数" }]);
  });

  it("整行空白跳过而非报错（Excel 常在末尾留空行）", () => {
    const r = parseImport<Row>("机柜号,SN,仓位数\nCAB1000,SN001,8\n,,\n", COLS);
    expect(r.errors).toEqual([]);
    expect(r.rows).toHaveLength(1);
  });

  it("空文件 / 只有表头都给出可读提示", () => {
    expect(parseImport<Row>("", COLS).errors[0].message).toBe("文件为空");
    expect(parseImport<Row>("型号\nX6\n", COLS).errors[0].message).toContain("缺少必填列");
    expect(parseImport<Row>("机柜号,SN,仓位数\n", COLS).errors[0].message).toBe("只有表头，没有数据行");
  });
});

describe("templateCsv", () => {
  it("带 BOM，表头与列定义一致，示例行可填", () => {
    const t = templateCsv(COLS, { 机柜号: "CAB1000", SN: "SN90000", 仓位数: "8", 型号: "X6" });
    expect(t.charCodeAt(0)).toBe(0xfeff);
    expect(parseCsv(t)).toEqual([
      ["机柜号", "SN", "仓位数", "型号"],
      ["CAB1000", "SN90000", "8", "X6"],
    ]);
  });
});
