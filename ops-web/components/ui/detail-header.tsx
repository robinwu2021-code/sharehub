// 详情抽屉 / 详情页统一的头（方案 C4）：业务号 + 名称 + 状态徽标 + 步骤条 + 动作。
// 只负责排版，不含业务 —— 徽标、步骤条、动作都由调用方以节点传入。
import { cn } from "@/lib/utils";

export function DetailHeader({
  no, title, badge, stepper, actions, meta, className,
}: {
  /** 业务号（等宽数字）。 */
  no: string;
  /** 对象名称（站点名、柜子型号…），可空。 */
  title?: React.ReactNode;
  /** 一般是 `<StatusBadge>`。 */
  badge?: React.ReactNode;
  /** 一般是 `<StatusStepper>`。 */
  stepper?: React.ReactNode;
  /** 一般是 `<StateActions>`。 */
  actions?: React.ReactNode;
  /** 名称下方的一行辅助信息（所属站点、责任人…）。 */
  meta?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("space-y-3 border-b border-border pb-4", className)}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="txt-strong tabular-nums">{no}</span>
            {badge}
          </div>
          {title && <div className="txt-heading truncate">{title}</div>}
          {meta && <div className="txt-caption text-muted-foreground">{meta}</div>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {stepper}
    </div>
  );
}
