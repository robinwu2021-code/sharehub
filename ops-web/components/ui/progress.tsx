"use client";

// 占比进度条：「已用/总数 (百分比)」+ 细条。
// 收敛自 devices 的 ProgressBar 与 users 的 QuotaBar（B4 各自手搓了一份，
// B3 的「版本灰度比例」会是第三处）——统一到共享件，避免继续发散。
import { cn } from "@/lib/utils";

export function Progress({
  value, total, warnAt, showText = true, className,
}: {
  value: number;
  total: number;
  /** 达到该百分比转为 danger 色（如额度用尽预警传 90）；不传则恒用主色 */
  warnAt?: number;
  /** 关掉则只留细条（如灰度比例只想要条） */
  showText?: boolean;
  className?: string;
}) {
  const pct = total > 0 ? Math.min(100, Math.round((value / total) * 100)) : 0;
  const warn = warnAt !== undefined && pct >= warnAt;
  return (
    <div className={cn("min-w-28", className)}>
      {showText && (
        <div className="mb-1 tabular-nums">
          {value}/{total} <span className="text-muted-foreground">({pct}%)</span>
        </div>
      )}
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
        <div
          className={cn("h-full rounded-full", warn ? "bg-[var(--destructive)]" : "bg-primary")}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
