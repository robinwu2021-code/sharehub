"use client";

// 列表页筛选标签（组合层）：一排可点的圆角标签，单选，第一个是「全部」。
// 与 FilterSelect 同一组参数 —— 选项少（≤ 6）且需要一眼看全时用它，多了仍用下拉。
//
// 为什么用药丸圆角：规范里药丸**只留给徽章 / 状态芯片**（「标签」隐喻）；筛选标签本身就是标签，
// 原型（16/13 工单工作台）也是这个形态。按钮、输入框仍是控件档 6px。
import * as React from "react";
import { cn } from "@/lib/utils";
import { statusOptions, type StatusMap } from "./status-badge";
import type { FilterOption } from "./filter-select";

export function FilterChips<K extends string>({
  value, onChange, options, allLabel, className, "aria-label": ariaLabel,
}: {
  value: string;
  onChange: (v: string) => void;
  options: StatusMap<K> | readonly FilterOption[];
  /** 「全部」项文案；不传则不出（必选型筛选） */
  allLabel?: string;
  className?: string;
  "aria-label"?: string;
}) {
  const list = React.useMemo(
    () => (Array.isArray(options) ? (options as readonly FilterOption[]) : statusOptions(options as StatusMap<K>)),
    [options],
  );
  const items = allLabel !== undefined ? [{ value: "", label: allLabel }, ...list] : list;
  return (
    <div role="radiogroup" aria-label={ariaLabel} className={cn("flex flex-wrap items-center gap-2", className)}>
      {items.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value || "__all"}
            type="button"
            role="radio"
            aria-checked={on}
            data-slot="filter-chip"
            data-state={on ? "on" : "off"}
            onClick={() => onChange(o.value)}
            className={cn(
              "rounded-chip border px-3.5 py-1 txt-caption transition-colors",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              on
                ? "border-[var(--primary)] bg-[var(--accent-subtle)] text-[var(--primary-text)]"
                : "border-[var(--border)] bg-card text-muted-foreground hover:border-[var(--primary)] hover:text-[var(--primary-text)]",
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
