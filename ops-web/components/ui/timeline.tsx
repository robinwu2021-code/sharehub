// 审计时间线（组合层）：金额/分值类操作的留痕列表。
//
// 形态取自实测的两处调用方（订单干预历史 / 信用分调分历史），四段结构是它们的并集：
//   [徽标] 单号 · 时间 · 操作人      ← badge + meta
//   前值 → 后值（可带金额）           ← change（可选）
//   原因/结论                        ← text（可选）
// 调用方只给数据，不给 DOM —— 时间线的缩进、竖线、行距在这里定死一份。
import * as React from "react";
import { Badge, type BadgeTone } from "./badge";

export interface TimelineItem {
  /** 列表 key，通常是留痕单号 */
  key: string;
  /** 左侧徽标：动作名（干预历史）或分值变化（调分历史） */
  badge?: { label: string; tone: BadgeTone };
  /** 单号 · 时间 · 操作人，小字弱化 */
  meta: React.ReactNode;
  /** 变化描述：`前 → 后`。数字类请自行包一层 tabular-nums */
  change?: React.ReactNode;
  /** 原因 / 结论正文 */
  text?: React.ReactNode;
}

export function Timeline({
  items, loading, empty, loadingText = "加载中…",
}: {
  items: TimelineItem[];
  loading?: boolean;
  /** 空列表文案（与 DataTable 的 empty 同义：要写清「为什么空」）*/
  empty: string;
  loadingText?: string;
}) {
  if (loading) return <span className="text-muted-foreground">{loadingText}</span>;
  if (!items.length) return <span className="text-muted-foreground">{empty}</span>;
  return (
    <ol className="space-y-2.5">
      {items.map((it) => (
        <li key={it.key} className="border-l-2 border-[var(--border)] pl-3">
          <div className="flex flex-wrap items-center gap-1.5">
            {it.badge && <Badge tone={it.badge.tone}>{it.badge.label}</Badge>}
            <span className="text-xs text-muted-foreground tabular-nums">{it.meta}</span>
          </div>
          {it.change != null && <div className="text-xs text-muted-foreground">{it.change}</div>}
          {it.text != null && <div className="text-sm">{it.text}</div>}
        </li>
      ))}
    </ol>
  );
}
