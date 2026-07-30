"use client";

// 语言切换器（顶栏）：伸缩式 —— 默认只显当前语言，hover/聚焦时展开另外两项。
// 三段常驻太占顶栏宽度，而切语言是低频操作，收起是合理默认。
import { useLocaleStore, type Locale } from "@/lib/stores/locale";
import { cn } from "@/lib/utils";

const OPTS: { key: Locale; short: string; label: string }[] = [
  { key: "zh", short: "中", label: "中文" },
  { key: "en", short: "EN", label: "English" },
  { key: "ar", short: "ع", label: "العربية" },
];

export function LangSwitcher() {
  const { locale, setLocale } = useLocaleStore();
  // 当前语言排最前，展开时另外两项从后面滑出
  const sorted = [...OPTS].sort((a, b) => (a.key === locale ? -1 : b.key === locale ? 1 : 0));
  return (
    <div
      className="group flex items-center gap-0.5 rounded-field bg-secondary p-0.5"
      role="group"
      aria-label="Language"
    >
      {sorted.map((o) => {
        const active = o.key === locale;
        return (
          <button
            key={o.key}
            type="button"
            onClick={() => setLocale(o.key)}
            aria-pressed={active}
            title={o.label}
            className={cn(
              "rounded-control px-2 py-0.5 text-xs transition-all duration-[var(--dur)] ease-[var(--ease)]",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              active
                ? "bg-card font-semibold text-foreground shadow-[var(--card-shadow)]"
                : // 非当前项收起：宽度归零，hover/键盘聚焦容器时展开
                  "max-w-0 overflow-hidden px-0 text-muted-foreground opacity-0 group-focus-within:max-w-12 group-focus-within:px-2 group-focus-within:opacity-100 group-hover:max-w-12 group-hover:px-2 group-hover:opacity-100 hover:text-foreground",
            )}
          >
            {o.short}
          </button>
        );
      })}
    </div>
  );
}
