// 通用查询/CRUD helper（原样搬运自 db.ts）。
// 覆盖：分页 paginate / 关键词命中 kwHit / 新增编辑 upsert / 业务号生成 nextNo。
import type { PageResult } from "../../types";

export function paginate<T>(all: T[], page = 1, size = 10, filter?: (t: T) => boolean): PageResult<T> {
  const rows = filter ? all.filter(filter) : all;
  const start = (page - 1) * size;
  return { list: rows.slice(start, start + size), total: rows.length, page, size };
}
export const kwHit = (kw: string | undefined, ...fields: (string | null | undefined)[]) =>
  !kw || fields.some((f) => (f ?? "").toLowerCase().includes(kw.toLowerCase()));

/** 通用 mock 新增/编辑：有业务键→就地更新，无→生成键后置顶插入。返回落地记录。 */
export function upsert<T>(
  arr: T[], item: Partial<T>, keyField: keyof T, mkKey: () => string,
): T {
  const key = item[keyField] as unknown as string | undefined;
  if (key) {
    const i = arr.findIndex((x) => (x[keyField] as unknown as string) === key);
    if (i >= 0) { arr[i] = { ...arr[i], ...item }; return arr[i]; }
  }
  const created = { ...item, [keyField]: key || mkKey() } as T;
  arr.unshift(created);
  return created;
}
/**
 * 生成新业务号：前缀 + 递增序号，**取现有同前缀号的最大值 +1**。
 *
 * 旧实现是 `base + arr.length`，有两个必然撞号的场景（2026-07-29 结构治理发现的真 bug）：
 *   1. 数据从 `base+1` 起编号（如 PB901…PB909 共 9 条）→ 生成 `base+9` = PB909 **与既有重复**；
 *      实测 `saveProblem` 与 `saveNotifyBlacklist` 都会 upsert 出同号两条。
 *   2. 删一条再新增（软删除约定下暂不触发，但一旦支持硬删就必撞）。
 * 现在扫描数组里所有 `前缀+数字` 形式的键取最大值，与起始编号无关、与增删无关。
 *
 * @param keyField 业务键字段名；不传则退化为扫描每个元素的全部字符串值（兼容旧调用）
 */
export const nextNo = (prefix: string, arr: unknown[], base = 900, keyField?: string) => {
  const re = new RegExp(`^${prefix}(\\d+)$`);
  let max = base - 1;
  for (const row of arr) {
    if (!row || typeof row !== "object") continue;
    const rec = row as Record<string, unknown>;
    const vals = keyField ? [rec[keyField]] : Object.values(rec);
    for (const v of vals) {
      if (typeof v !== "string") continue;
      const m = re.exec(v);
      if (m) max = Math.max(max, Number(m[1]));
    }
  }
  return `${prefix}${max + 1}`;
};
