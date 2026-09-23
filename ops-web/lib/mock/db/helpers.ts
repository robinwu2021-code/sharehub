// 通用查询/CRUD helper（原样搬运自 db.ts）。
// 覆盖：分页 paginate / 关键词命中 kwHit / 新增编辑 upsert / 业务号生成 nextNo /
//       归档过滤 liveHit + 归档落库 setArchived（G1 软删除）。
import type { PageResult, Archivable } from "../../types";
import { notFound } from "@/lib/biz-error";

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

// ————————————————————————————————————————————————————————————————
// G1 软删除（TDD §10.1）：归档而非删除
// ————————————————————————————————————————————————————————————————

/**
 * 列表默认过滤已归档行的谓词。**每个可归档实体的 list 都必须串上它**，
 * 否则「归档了还在列表里」——这是软删除最常见的漏实现。
 *
 * `showArchived` 从 URL/查询参数来，可能是 boolean 也可能是字符串 "1"/"true"，
 * 故此处做宽松解释（http-client 的 qs() 会把 boolean 序列化成字符串）。
 */
export const liveHit = (row: { archivedAt?: string | null }, showArchived?: unknown) =>
  showArchived === true || showArchived === "1" || showArchived === "true" ? true : !row.archivedAt;

/** 归档/恢复落库：按业务键就地改 `archivedAt`。找不到直接抛——前端不该调到不存在的行。 */
export function setArchived<T extends Archivable>(
  arr: T[], keyField: keyof T, key: string, at: string | null,
): T {
  const i = arr.findIndex((x) => (x[keyField] as unknown as string) === key);
  if (i < 0) throw notFound("记录", "Record", key);
  arr[i] = { ...arr[i], archivedAt: at };
  return arr[i];
}

/** 归档：盖当前时间戳。 */
export const archiveRow = <T extends Archivable>(arr: T[], keyField: keyof T, key: string) =>
  setArchived(arr, keyField, key, new Date().toISOString());

/** 恢复：清空归档时间，回到默认列表。 */
export const unarchiveRow = <T extends Archivable>(arr: T[], keyField: keyof T, key: string) =>
  setArchived(arr, keyField, key, null);
