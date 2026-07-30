"use client";

// 列表页筛选下拉（组合层）：Select + 一个「全部 XX」空值项。
//
// 抽它不是为了省 <Select> 那一层，是为了消掉**筛选项文案与徽标文案两处维护**：
// 传 StatusMap 时选项直接由映射表派生，改文案只改一处。
// 没有对应映射表的（如按平台/国家筛）传 options 数组，形态与 FieldDef.options 一致。
import * as React from "react";
import { cn } from "@/lib/utils";
import { Select } from "./input";
import { statusOptions, type StatusMap } from "./status-badge";

export type FilterOption = { value: string; label: string };

export function FilterSelect<K extends string>({
  value, onChange, options, allLabel, className, "aria-label": ariaLabel,
}: {
  value: string;
  /** 直接给值（不是 event）——筛选器的回调无一例外只用 e.target.value */
  onChange: (v: string) => void;
  /** StatusMap：选项由映射表派生（顺序即键序）；数组：显式给定 */
  options: StatusMap<K> | readonly FilterOption[];
  /** 空值项文案，如「全部状态」。不传则不出空值项（用于必选型筛选，如结算周期） */
  allLabel?: string;
  className?: string;
  "aria-label"?: string;
}) {
  const list = React.useMemo(
    () => (Array.isArray(options) ? (options as readonly FilterOption[]) : statusOptions(options as StatusMap<K>)),
    [options],
  );
  // 与搜索框、表单 Select 同为控件档 6px —— 工具栏一行里形状必须一致。
  return (
    <Select className={cn(className)} aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)}>
      {allLabel !== undefined && <option value="">{allLabel}</option>}
      {list.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </Select>
  );
}
