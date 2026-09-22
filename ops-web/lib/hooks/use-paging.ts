"use client";

// 列表分页状态：页码 + 每页条数。
//
// **为什么绑在一起**：换了每页条数还停在第 5 页，很可能落到一个不存在的页 ——
// 表格空白，看着像「筛没了」。`setSize` 内部强制把页码复位到 1，调用方想漏都漏不掉。
//
// 整理前 29 个页面里有 17 个各写一份 `useState(1)`，其中没有一个做了这个复位。
import * as React from "react";
import { PAGE_SIZE } from "@/lib/constants";

export interface Paging {
  page: number;
  setPage: (p: number) => void;
  size: number;
  setSize: (n: number) => void;
  /** 筛选条件变化时调用：回到第 1 页。与 setPage(1) 等价，但读起来是「为什么」而不是「做什么」 */
  reset: () => void;
}

export function usePaging(initialSize: number = PAGE_SIZE): Paging {
  const [page, setPage] = React.useState(1);
  const [size, setSizeState] = React.useState(initialSize);
  const setSize = React.useCallback((n: number) => {
    setSizeState(n);
    setPage(1);
  }, []);
  const reset = React.useCallback(() => setPage(1), []);
  return { page, setPage, size, setSize, reset };
}
