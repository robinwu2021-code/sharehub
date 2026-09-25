// 横向步骤条（方案 C3）：已过 / 当前 / 未到三态 + 分支终态（驳回、终止、撤场）。
//
// 当前步用**形状 + 字重**（实心点 + txt-strong）表达，不只靠颜色（规范 §11.4）。
// 窄屏（<640px）折叠为一行「第 n / m 步 · 名称」—— 五六个步骤横排在手机上只剩一串点。
// 方向用 flex 自然流，RTL 下随 dir 翻转，不写死 left/right。
import { cn } from "@/lib/utils";

export interface Step {
  key: string;
  label: string;
}

export function StatusStepper({
  steps, current, branch, className,
}: {
  steps: Step[];
  /** 当前所在步的 key。不在 steps 里（例如处于分支终态）时，配合 branch 使用。 */
  current: string;
  /**
   * 分支终态：流程没走完就结束了（驳回 / 终止 / 撤场）。
   * `after` = 从哪一步岔出去的；该步之后的步骤显示为未到，并在末尾追加这个终态。
   */
  branch?: { label: string; after: string } | null;
  className?: string;
}) {
  const idx = branch ? steps.findIndex((s) => s.key === branch.after) : steps.findIndex((s) => s.key === current);
  const total = steps.length + (branch ? 1 : 0);
  const currentLabel = branch ? branch.label : steps[idx]?.label ?? current;
  const currentPos = branch ? total : idx + 1;

  return (
    <div className={className}>
      {/* 窄屏：一行文字 */}
      <div className="txt-caption text-muted-foreground sm:hidden">
        第 {currentPos} / {total} 步 · <span className="txt-strong text-foreground">{currentLabel}</span>
      </div>
      {/* 宽屏：横向步骤 */}
      <ol className="hidden items-center gap-1.5 sm:flex" aria-label="流程进度">
        {steps.map((s, i) => {
          const state = branch ? (i <= idx ? "done" : "todo") : i < idx ? "done" : i === idx ? "current" : "todo";
          return (
            <li key={s.key} className="flex min-w-0 items-center gap-1.5" aria-current={state === "current" ? "step" : undefined}>
              {i > 0 && <span className={cn("h-px w-4 shrink-0", state === "todo" ? "bg-border" : "bg-primary")} />}
              <span
                className={cn(
                  "size-2 shrink-0 rounded-chip",
                  state === "current" && "bg-primary ring-2 ring-primary/30",
                  state === "done" && "bg-primary",
                  state === "todo" && "border border-border bg-transparent",
                )}
              />
              <span
                className={cn(
                  "truncate",
                  state === "current" ? "txt-strong" : "txt-caption",
                  state === "todo" && "text-muted-foreground",
                )}
              >
                {s.label}
              </span>
            </li>
          );
        })}
        {branch && (
          <li className="flex min-w-0 items-center gap-1.5" aria-current="step">
            <span className="h-px w-4 shrink-0 bg-destructive" />
            <span className="size-2 shrink-0 rounded-chip bg-destructive ring-2 ring-destructive/30" />
            <span className="truncate txt-strong text-destructive-ink">{branch.label}</span>
          </li>
        )}
      </ol>
    </div>
  );
}
