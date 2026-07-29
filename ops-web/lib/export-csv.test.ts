// export-csv 纯函数单测：BOM / 转义 / 空值 / 文件名日期后缀。
import { describe, it, expect } from "vitest";
import { toCsv, escapeCsvCell, csvFilename, CSV_BOM, type CsvColumn } from "./export-csv";

interface Row { name: string; qty: number | null; note?: string }

const cols: CsvColumn<Row>[] = [
  { header: "名称", value: (r) => r.name },
  { header: "数量", value: (r) => r.qty },
  { header: "备注", value: (r) => r.note },
];

describe("escapeCsvCell", () => {
  it("空值 → 空串", () => {
    expect(escapeCsvCell(null)).toBe("");
    expect(escapeCsvCell(undefined)).toBe("");
    expect(escapeCsvCell("")).toBe("");
  });
  it("数字原样", () => expect(escapeCsvCell(0)).toBe("0"));
  it("普通文本不加引号", () => expect(escapeCsvCell("abc 中文")).toBe("abc 中文"));
  it("含逗号加引号", () => expect(escapeCsvCell("a,b")).toBe('"a,b"'));
  it("含引号翻倍并包裹", () => expect(escapeCsvCell('say "hi"')).toBe('"say ""hi"""'));
  it("含换行包裹", () => expect(escapeCsvCell("l1\nl2")).toBe('"l1\nl2"'));
  it("含回车包裹", () => expect(escapeCsvCell("l1\r\nl2")).toBe('"l1\r\nl2"'));
});

describe("toCsv", () => {
  it("以 UTF-8 BOM 开头（Excel 中文不乱码）", () => {
    expect(toCsv(cols, [])).toBe(`${CSV_BOM}名称,数量,备注`);
    expect(toCsv(cols, []).charCodeAt(0)).toBe(0xfeff);
  });
  it("表头 + 数据行，CRLF 分隔", () => {
    const csv = toCsv(cols, [{ name: "A", qty: 1, note: "x" }, { name: "B", qty: 2 }]);
    expect(csv).toBe(`${CSV_BOM}名称,数量,备注\r\nA,1,x\r\nB,2,`);
  });
  it("空值列输出空串而非 null/undefined", () => {
    const csv = toCsv(cols, [{ name: "A", qty: null }]);
    expect(csv.split("\r\n")[1]).toBe("A,,");
  });
  it("含逗号/引号/换行的字段被正确转义", () => {
    const csv = toCsv(cols, [{ name: 'a,b', qty: 1, note: 'q"n\nx' }]);
    expect(csv.split("\r\n")[1]).toBe('"a,b",1,"q""n\nx"');
  });
  it("空列配置只输出 BOM + 空行", () => expect(toCsv<Row>([], [])).toBe(CSV_BOM));
});

describe("csvFilename", () => {
  it("追加日期后缀", () => expect(csvFilename("发送记录", new Date(2026, 6, 29))).toBe("发送记录-2026-07-29.csv"));
  it("月日补零", () => expect(csvFilename("x", new Date(2026, 0, 5))).toBe("x-2026-01-05.csv"));
  it("已带 .csv 不重复", () => expect(csvFilename("x.csv", new Date(2026, 6, 29))).toBe("x-2026-07-29.csv"));
});
