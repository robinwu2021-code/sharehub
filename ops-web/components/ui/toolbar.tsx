"use client";

// 列表页统一工具条：搜索框 + 筛选槽(children) + 右侧操作（导出 / 新增）。
// selectedCount > 0 时整条替换为「批量操作条」（静态色块，不用浮层——静态导出下简单可靠）。
import * as React from "react";
import { Plus, Download, X } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { useI18n } from "@/lib/i18n";

export function Toolbar({
  search, onSearch, searchPlaceholder, children, onAdd, addLabel, canAdd = true,
  onExport, exportLabel,
  selectedCount = 0, batchActions, onClearSelection,
}: {
  search?: string;
  onSearch?: (v: string) => void;
  searchPlaceholder?: string;
  children?: React.ReactNode; // 额外筛选（Select 等）
  onAdd?: () => void;
  addLabel?: string;
  canAdd?: boolean;
  /** 传了才显示「导出」按钮（次要样式，位于新增左侧） */
  onExport?: () => void;
  exportLabel?: string;
  /** >0 时切换为批量操作条 */
  selectedCount?: number;
  batchActions?: React.ReactNode;
  onClearSelection?: () => void;
}) {
  const { t } = useI18n();

  if (selectedCount > 0) {
    return (
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-card bg-accent px-3.5 py-2">
        <span className="text-sm font-medium">{t("table.selectedN", { n: selectedCount })}</span>
        <div className="flex flex-wrap items-center gap-2">{batchActions}</div>
        {onClearSelection && (
          <Button size="sm" variant="ghost" className="ms-auto" onClick={onClearSelection}>
            <X className="size-4" /> {t("table.clearSelection")}
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      {onSearch !== undefined && (
        <Input
          // 搜索框是 C 端唯一用药丸的输入（首页 search）；表单输入走 Input 默认的 11px
          className="w-60 rounded-full"
          placeholder={searchPlaceholder ?? t("common.search")}
          value={search ?? ""}
          onChange={(e) => onSearch(e.target.value)}
        />
      )}
      {children}
      {onExport && (
        <Button size="sm" variant="secondary" className="ms-auto" onClick={onExport}>
          <Download className="size-4" /> {exportLabel ?? t("export.label")}
        </Button>
      )}
      {onAdd && canAdd && (
        <Button size="sm" className={onExport ? undefined : "ms-auto"} onClick={onAdd}>
          <Plus className="size-4" /> {addLabel ?? t("common.add")}
        </Button>
      )}
    </div>
  );
}
