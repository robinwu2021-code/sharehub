"use client";

import * as React from "react";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";

// 分段控件（segmented）：浅色块容器内，激活 tab = 实心白 pill + 极轻阴影。取代下划线分割。
// 形状规格（灰槽/全圆/字重）与 `ui/tab-header.tsx` 里的同类分段控件共用 `ui/segmented.ts`。
export function Tabs({
  tabs, value, onChange,
}: { tabs: { key: string; label: string }[]; value: string; onChange: (k: string) => void }) {
  return (
    <div className={segmentedTrackClass("mb-5 max-w-full flex-wrap")}>
      {tabs.map((t) => (
        <button
          key={t.key}
          onClick={() => onChange(t.key)}
          className={segmentedItemClass(value === t.key, "px-3.5 py-1.5 text-sm")}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
