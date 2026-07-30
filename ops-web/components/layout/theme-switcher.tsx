"use client";

// 色块主题切换器：点开一格调色板，每个主题一个圆润色块，白底不变。
import { useEffect, useRef, useState } from "react";
import { Check, Palette } from "lucide-react";
import { THEMES, useTheme } from "@/lib/stores/theme";
import { cn } from "@/lib/utils";

export function ThemeSwitcher() {
  const { themeKey, setTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const current = THEMES.find((t) => t.key === themeKey) ?? THEMES[0];

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label="切换主题色"
        className="flex h-8 items-center gap-1.5 rounded-lg bg-secondary px-2.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
      >
        <Palette className="size-4" />
        <span className="size-3.5 rounded-full ring-1 ring-border" style={{ background: current.color }} />
      </button>

      {open && (
        <div className="absolute right-0 top-10 z-50 w-56 rounded-xl bg-card p-3 shadow-lg">
          <div className="mb-2 px-1 text-xs font-medium text-muted-foreground">主题色</div>
          <div className="grid grid-cols-3 gap-2">
            {THEMES.map((t) => {
              const active = t.key === themeKey;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTheme(t.key)}
                  title={t.label}
                  aria-label={t.label}
                  aria-pressed={active}
                  className={cn(
                    // ring 必须用 --border 而不是写死 black/5：黑白灰皮肤的色卡本身
                    // 就是近黑，在暗色弹层上会整块隐形（实测只看得到 2 个色卡）。
                    "flex aspect-square items-center justify-center rounded-xl ring-1 ring-border transition-transform hover:scale-105",
                    active && "ring-2 ring-offset-2 ring-offset-card",
                  )}
                  style={{ background: t.color, boxShadow: active ? `0 0 0 2px ${t.color}` : undefined }}
                >
                  {active && <Check className="size-4 text-white" strokeWidth={3} />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
