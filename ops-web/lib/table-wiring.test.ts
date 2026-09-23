// 列表接线棘轮：这三个数字**只允许下降**。
//
// 它们不是风格偏好，每一个都对应一类用户看得见的故障：
//
//  1. 裸 `<DataTable>` 未接 `error` —— 接口失败时画成「暂无数据」，
//     运营去改筛选条件而不是报障（整理前 125 个调用点里接了的是 0 个）。
//  2. `size: <数字>` 字面量 —— 一次取完绕开分页。配置表可以这么做，
//     但要用 `UNPAGED_SIZE` 表明「这张表设计上有界」，而不是随手写个 200。
//  3. 手写 `useState(1)` 分页 —— 换每页条数不复位页码 → 落到不存在的页 → 空表。
//
// **加新页面时不要抬这些数字**：分页列表用 `<PagedTable>`（五项没地方漏），
// 有界配置表用 `<DataTable>` + `UNPAGED_SIZE` 且自己接上 `error`/`onRetry`。
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "..");

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === "node_modules" || name === ".next") continue;
      walk(p, out);
    } else if (name.endsWith(".tsx")) out.push(p);
  }
  return out;
}

/** 调用点层面：`components/ui/` 是库本身，`app/dev/` 是组件陈列页（演示数据，不取数） */
const CALL_SITES = [...walk(join(ROOT, "app")), ...walk(join(ROOT, "components"))].filter(
  (p) => !p.includes("/components/ui/") && !p.includes("/app/dev/"),
);

const read = (p: string) => readFileSync(p, "utf8");

describe("列表接线棘轮（数字只许降）", () => {
  it("裸 DataTable 未接 error 的调用点 ≤ 111", () => {
    let n = 0;
    for (const p of CALL_SITES) {
      const s = read(p);
      for (const m of s.matchAll(/<DataTable\b/g)) {
        const seg = s.slice(m.index!, m.index! + 1200);
        const end = seg.indexOf("/>");
        if (!(end > 0 ? seg.slice(0, end) : seg).includes("error")) n++;
      }
    }
    expect(n).toBeLessThanOrEqual(111);
  });

  it("页面层的 size 数字字面量 ≤ 36", () => {
    let n = 0;
    for (const p of CALL_SITES) n += [...read(p).matchAll(/size: \d{2,}/g)].length;
    expect(n).toBeLessThanOrEqual(36);
  });

  it("手写 useState(1) 分页的文件 ≤ 15", () => {
    const n = CALL_SITES.filter((p) => /useState\(1\)/.test(read(p))).length;
    expect(n).toBeLessThanOrEqual(15);
  });
});
