"use client";

// 列表页统一工具条：搜索框 + 筛选槽(children) + 右侧主操作(新增)。
// 让所有列表 tab 的「搜索/筛选/新增」布局一致。
import * as React from "react";
import { Plus } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { useI18n } from "@/lib/i18n";

export function Toolbar({
  search, onSearch, searchPlaceholder, children, onAdd, addLabel, canAdd = true,
}: {
  search?: string;
  onSearch?: (v: string) => void;
  searchPlaceholder?: string;
  children?: React.ReactNode; // 额外筛选（Select 等）
  onAdd?: () => void;
  addLabel?: string;
  canAdd?: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      {onSearch !== undefined && (
        <Input
          className="w-60"
          placeholder={searchPlaceholder ?? t("common.search")}
          value={search ?? ""}
          onChange={(e) => onSearch(e.target.value)}
        />
      )}
      {children}
      {onAdd && canAdd && (
        <Button size="sm" className="ms-auto" onClick={onAdd}>
          <Plus className="size-4" /> {addLabel ?? t("common.add")}
        </Button>
      )}
    </div>
  );
}
