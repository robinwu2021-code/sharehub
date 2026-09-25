// 可点击筛选的摘要卡（规则 R2：一张卡 = 一个待办子集）。
//
// 包一层 button 而不是给 SummaryCard 加 onClick：SummaryCard 是只读原语，
// 这里多出来的是「选中态 + 键盘可达」，属于交互层。再点一次取消筛选。
import { SummaryCard } from "@/components/ui/summary-card";
import { cn } from "@/lib/utils";

export function FilterCard({
  label, value, sub, active, onClick,
}: {
  label: string;
  value: React.ReactNode;
  sub?: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={!!active}
      onClick={onClick}
      className={cn(
        "rounded-card text-start transition-shadow duration-[var(--dur-fast)] ease-[var(--ease)]",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        active && "ring-2 ring-primary",
      )}
    >
      <SummaryCard label={label} value={value} sub={sub} />
    </button>
  );
}
