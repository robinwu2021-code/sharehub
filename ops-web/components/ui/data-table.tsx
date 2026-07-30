"use client";

import * as React from "react";
import { ChevronRight, ChevronUp, ChevronDown, ChevronsUpDown } from "lucide-react";
import { Card } from "./card";
import { Checkbox } from "./checkbox";
import { Table, THead, TBody, TR, TH, TD } from "./table";
import { Skeleton, EmptyState } from "./misc";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

export interface Column<T> {
  header: string;
  cell: (row: T) => React.ReactNode;
  className?: string;
  /** 设了才可排序：点表头切 asc/desc（受控，实际排序由调用方做） */
  sortKey?: string;
}

export type SortDir = "asc" | "desc";

/**
 * 行选择 checkbox（含半选态）。
 *
 * 原为就地实现的原生 `<input type=checkbox>`（靠 ref 副作用设 `indeterminate`），
 * 已上移为原语 `ui/checkbox.tsx`；这里只留「三态 boolean → CheckedState」的转接
 * 与 `stopPropagation`（行整体可点，勾选不该顺带打开详情）。
 */
function RowCheckbox({
  checked, indeterminate, onChange, label,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange: (v: boolean) => void;
  label: string;
}) {
  return (
    <span onClick={(e) => e.stopPropagation()} className="inline-flex">
      <Checkbox
        aria-label={label}
        checked={checked ? true : indeterminate ? "indeterminate" : false}
        // 半选被点击时 Radix 给 true（半选 → 全选），与全选列的直觉一致。
        onChange={(v) => onChange(v === true)}
      />
    </span>
  );
}

// 通用列表表格：列配置 + 行数据 + 加载/空态。让新列表页保持一致、精简。
// 可选能力（不传即与旧行为完全一致）：行选择 / 行展开 / 受控排序。
export function DataTable<T>({
  columns, rows, loading, rowKey, empty,
  selectable, selectedKeys, onSelectedChange,
  expandable,
  sortKey, sortDir, onSortChange,
  rowClassName,
}: {
  columns: Column<T>[];
  rows: T[] | undefined;
  loading?: boolean;
  rowKey: (row: T) => string;
  empty?: string;
  /** 显示行选择 checkbox 列（最左） */
  selectable?: boolean;
  selectedKeys?: string[];
  onSelectedChange?: (keys: string[]) => void;
  /** 返回展开内容则该行可展开（展开箭头列紧随选择列） */
  expandable?: (row: T) => React.ReactNode;
  sortKey?: string;
  sortDir?: SortDir;
  onSortChange?: (key: string, dir: SortDir) => void;
  /**
   * 行级样式钩子：整行强调/弱化（如 预约即将超时高亮、白名单过期灰显、套餐下架灰显）。
   * `Column.className` 只能到列级，行级状态表达不了 —— B0 首版遗漏，2026-07-29 补。
   */
  rowClassName?: (row: T) => string | undefined;
}) {
  const { t } = useI18n();
  const emptyText = empty ?? t("common.empty");
  const [expanded, setExpanded] = React.useState<string[]>([]);

  const selected = React.useMemo(() => new Set(selectedKeys ?? []), [selectedKeys]);
  const allKeys = React.useMemo(() => (rows ?? []).map(rowKey), [rows, rowKey]);
  const selectedOnPage = allKeys.filter((k) => selected.has(k)).length;
  const allChecked = allKeys.length > 0 && selectedOnPage === allKeys.length;
  const someChecked = selectedOnPage > 0 && !allChecked;

  const toggleAll = (v: boolean) => {
    if (!onSelectedChange) return;
    const rest = (selectedKeys ?? []).filter((k) => !allKeys.includes(k));
    onSelectedChange(v ? [...rest, ...allKeys] : rest);
  };
  const toggleOne = (k: string, v: boolean) => {
    if (!onSelectedChange) return;
    const cur = selectedKeys ?? [];
    onSelectedChange(v ? (cur.includes(k) ? cur : [...cur, k]) : cur.filter((x) => x !== k));
  };
  const toggleExpand = (k: string) =>
    setExpanded((p) => (p.includes(k) ? p.filter((x) => x !== k) : [...p, k]));

  const leadCols = (selectable ? 1 : 0) + (expandable ? 1 : 0);
  const totalCols = columns.length + leadCols;

  const headerCell = (c: Column<T>, i: number) => {
    const sortable = !!c.sortKey && !!onSortChange;
    if (!sortable) return <TH key={i} className={c.className}>{c.header}</TH>;
    const active = sortKey === c.sortKey;
    const Icon = active ? (sortDir === "desc" ? ChevronDown : ChevronUp) : ChevronsUpDown;
    return (
      <TH key={i} className={c.className}>
        <button
          type="button"
          className={cn(
            "inline-flex items-center gap-1 rounded-chip transition-colors hover:text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
            active && "text-foreground",
          )}
          aria-sort={active ? (sortDir === "desc" ? "descending" : "ascending") : "none"}
          title={t("table.sortBy")}
          onClick={() => onSortChange!(c.sortKey!, active && sortDir === "asc" ? "desc" : "asc")}
        >
          {c.header}
          <Icon className={cn("size-3.5", !active && "opacity-50")} />
        </button>
      </TH>
    );
  };

  return (
    <Card className="overflow-hidden">
      {loading && !rows ? (
        <div className="space-y-2 p-4">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>
      ) : !rows || rows.length === 0 ? (
        <div className="p-4"><EmptyState title={emptyText} /></div>
      ) : (
        <Table>
          <THead>
            <TR>
              {selectable && (
                <TH className="w-10">
                  <RowCheckbox
                    checked={allChecked}
                    indeterminate={someChecked}
                    onChange={toggleAll}
                    label={t("table.selectAll")}
                  />
                </TH>
              )}
              {expandable && <TH className="w-10" />}
              {columns.map(headerCell)}
            </TR>
          </THead>
          <TBody>
            {rows.map((row) => {
              const k = rowKey(row);
              const content = expandable?.(row);
              const isOpen = expanded.includes(k);
              return (
                <React.Fragment key={k}>
                  <TR className={rowClassName?.(row)}>
                    {selectable && (
                      <TD className="h-[var(--row-h)] w-10">
                        <RowCheckbox
                          checked={selected.has(k)}
                          onChange={(v) => toggleOne(k, v)}
                          label={t("table.selectRow")}
                        />
                      </TD>
                    )}
                    {expandable && (
                      <TD className="h-[var(--row-h)] w-10">
                        {content ? (
                          <button
                            type="button"
                            className={cn(
                              "rounded-chip p-1 text-muted-foreground transition-colors hover:bg-accent",
                              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
                            )}
                            aria-expanded={isOpen}
                            aria-label={isOpen ? t("table.collapse") : t("table.expand")}
                            onClick={() => toggleExpand(k)}
                          >
                            <ChevronRight
                              className={cn("size-4 transition-transform rtl:-scale-x-100", isOpen && "rotate-90 rtl:rotate-90")}
                            />
                          </button>
                        ) : null}
                      </TD>
                    )}
                    {columns.map((c, i) => <TD key={i} className={cn("h-[var(--row-h)]", c.className)}>{c.cell(row)}</TD>)}
                  </TR>
                  {expandable && isOpen && content && (
                    <TR className="hover:bg-muted">
                      <TD colSpan={totalCols} className="bg-muted p-4">{content}</TD>
                    </TR>
                  )}
                </React.Fragment>
              );
            })}
          </TBody>
        </Table>
      )}
    </Card>
  );
}
