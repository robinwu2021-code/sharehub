"use client";

// 分页列表：`DataTable` + `Pagination` 绑在**同一份取数结果**上。
//
// ## 为什么要收这一件（不是为了少打字）
//
// 这两件此前是各页手拼的。一次盘点（125 个 `DataTable` 调用点、96 个 `useQuery`）里：
//
//   · 传了 `error` / `onRetry` 的：**0 个**。处理 `isError` 的页面：**1 个**。
//     ——接口 500 时表格渲染成「暂无数据」，运营会去改筛选条件，不会报障。
//   · `Pagination` 的 `onSize` 此前压根不存在，每页条数不可调，于是页面改用
//     `size: 200 / 500 / 999` 一次取完（38 处、6 种取值）绕开分页。
//
// 所以这里把接线收进组件：`query` 一交，**rows / loading / error / onRetry / total
// 五项没有地方可以漏**；`onSize` 从可选变成**必填** —— 编译期拦得住的东西，
// 不该留给 code review 去看。
//
// ## 判据
//
// **列表是分页的就用它。** 不分页的配置表继续直接用 `DataTable`（那是明确决定，
// 不是遗漏；此时该用 `UNPAGED_SIZE` 并在页面写明为什么这张表有界）。

import * as React from "react";
import { DataTable, type DataTableProps } from "./data-table";
import { Pagination } from "./misc";
import type { Paging } from "@/lib/hooks/use-paging";

/**
 * 取数结果的**结构化**契约 —— 刻意不 `import type { UseQueryResult }`：
 * `components/ui/` 不认任何取数库，换掉 TanStack Query 时这一层不该跟着改。
 * TanStack 的 `UseQueryResult<PageResult<T>>` 结构上满足它，直接传即可。
 */
export interface PagedQuery<T> {
  data?: { list: T[]; total: number };
  /** v5 语义：从没拿到过数据。`enabled:false` 的查询会一直为 true —— 那种情形自己传 `loading` */
  isPending?: boolean;
  isLoading?: boolean;
  error?: unknown;
  refetch?: () => unknown;
}

export type PagedTableProps<T> = Omit<
  DataTableProps<T>,
  "rows" | "loading" | "error" | "onRetry"
> & {
  query: PagedQuery<T>;
  /** 来自 `usePaging()`。页码与条数同源，换条数自动回第 1 页 */
  paging: Paging;
  /** 覆盖 query 的加载态（如 `enabled:false` 的依赖查询） */
  loading?: boolean;
  /** 列表下方、分页上方的补充说明（如「仅显示近 90 天」） */
  footer?: React.ReactNode;
};

export function PagedTable<T>({ query, paging, loading, footer, ...rest }: PagedTableProps<T>) {
  const rows = query.data?.list;
  const total = query.data?.total ?? 0;
  return (
    <>
      <DataTable
        {...rest}
        rows={rows}
        loading={loading ?? (query.isLoading ?? query.isPending ?? false)}
        error={query.error}
        onRetry={query.refetch ? () => query.refetch!() : undefined}
      />
      {footer}
      {/* 失败时不渲染分页条：total 是 0，画一个「共 0 条 · 1/1」等于替错误数据背书 */}
      {!query.error && (
        <Pagination
          page={paging.page}
          size={paging.size}
          total={total}
          onPage={paging.setPage}
          onSize={paging.setSize}
        />
      )}
    </>
  );
}
