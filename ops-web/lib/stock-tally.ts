// 库存余额的汇总（OM03-08）：按维度取值分组、逐状态计数，再补一行合计。
// 纯函数放在 lib 下以便单测；页面见 components/device/stock-balance.tsx。

/** 一行：维度取值 + 各状态计数。合计行的 key 固定为「合计」。 */
export type StockRow = { key: string; total: number; counts: Record<string, number> };

export function tally<T>(items: T[], keyOf: (x: T) => string, statusOf: (x: T) => string): StockRow[] {
  const map = new Map<string, StockRow>();
  for (const x of items) {
    const k = keyOf(x);
    const r = map.get(k) ?? { key: k, total: 0, counts: {} };
    r.total += 1;
    const s = statusOf(x);
    r.counts[s] = (r.counts[s] ?? 0) + 1;
    map.set(k, r);
  }
  // 数量多的在前：运营先看「货堆在哪」；同数按取值排，结果稳定
  return [...map.values()].sort((a, b) => b.total - a.total || a.key.localeCompare(b.key));
}

export function totalRow(rows: StockRow[]): StockRow {
  const counts: Record<string, number> = {};
  for (const r of rows) for (const [k, n] of Object.entries(r.counts)) counts[k] = (counts[k] ?? 0) + n;
  return { key: "合计", total: rows.reduce((s, r) => s + r.total, 0), counts };
}
