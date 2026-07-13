"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

// 分段控件（segmented）：浅色块容器内，激活 tab = 实心白 pill + 极轻阴影。取代下划线分割。
export function Tabs({
  tabs, value, onChange,
}: { tabs: { key: string; label: string }[]; value: string; onChange: (k: string) => void }) {
  return (
    <div className="mb-5 inline-flex max-w-full flex-wrap gap-1 rounded-xl bg-secondary p-1">
      {tabs.map((t) => (
        <button
          key={t.key}
          onClick={() => onChange(t.key)}
          className={cn(
            "rounded-lg px-3.5 py-1.5 text-sm transition-colors",
            value === t.key
              ? "bg-card font-medium text-foreground shadow-[var(--card-shadow)]"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
