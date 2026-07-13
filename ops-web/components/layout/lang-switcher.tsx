"use client";

// 语言切换器（顶栏）：zh / EN / ع 三段。扁平色块分段风格。
import { Languages } from "lucide-react";
import { useLocaleStore, type Locale } from "@/lib/stores/locale";
import { cn } from "@/lib/utils";

const OPTS: { key: Locale; short: string }[] = [
  { key: "zh", short: "中" },
  { key: "en", short: "EN" },
  { key: "ar", short: "ع" },
];

export function LangSwitcher() {
  const { locale, setLocale } = useLocaleStore();
  return (
    <div className="flex items-center gap-1 rounded-lg bg-secondary p-0.5" role="group" aria-label="Language">
      <Languages className="ms-1 size-3.5 text-muted-foreground" />
      {OPTS.map((o) => (
        <button
          key={o.key}
          type="button"
          onClick={() => setLocale(o.key)}
          aria-pressed={locale === o.key}
          className={cn(
            "rounded-md px-2 py-0.5 text-xs transition-colors",
            locale === o.key ? "bg-card font-medium text-foreground shadow-[var(--card-shadow)]" : "text-muted-foreground hover:text-foreground",
          )}
        >
          {o.short}
        </button>
      ))}
    </div>
  );
}
