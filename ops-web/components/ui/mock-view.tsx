"use client";

// 演示态内容视图：KPI 统计卡 + 数据表格（用于各域占位页落地真实观感）。
// 数据为前端本地演示样本（无后端），供 P2/P3 页面「有内容可看、菜单可点」。
import * as React from "react";
import { StatCard } from "./misc";
import { DataTable, type Column } from "./data-table";

export interface MockStat {
  label: string;
  value: React.ReactNode;
  sub?: string;
  tone?: "up" | "down";
}

export function StatGrid({ stats }: { stats: MockStat[] }) {
  if (!stats.length) return null;
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {stats.map((s) => (
        <StatCard key={s.label} label={s.label} value={s.value} sub={s.sub} tone={s.tone} />
      ))}
    </div>
  );
}

export function MockTabView<T extends { id: string }>({
  stats, columns, rows, empty,
}: {
  stats?: MockStat[];
  columns: Column<T>[];
  rows: T[];
  empty?: string;
}) {
  return (
    <div className="space-y-4">
      {stats && stats.length > 0 && <StatGrid stats={stats} />}
      <DataTable rowKey={(r) => r.id} columns={columns} rows={rows} empty={empty} />
    </div>
  );
}
