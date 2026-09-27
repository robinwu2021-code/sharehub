"use client";

// 外观切换器：上面一行选「风格」（整套色阶），下面一格选「主题色」（只换主色）。
// 两条轴正交，见 stores/style.ts 顶部说明。
import { useEffect, useRef, useState } from "react";
import { Check, Palette } from "lucide-react";
import { THEMES, useTheme } from "@/lib/stores/theme";
import { STYLES, useStyle } from "@/lib/stores/style";
import { useI18n } from "@/lib/i18n";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";
import { cn } from "@/lib/utils";

export function ThemeSwitcher() {
  const { themeKey, setTheme } = useTheme();
  const { styleKey, setStyle } = useStyle();
  const { t } = useI18n();
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
        aria-label={t("theme.label")}
        className="flex h-8 items-center gap-1.5 rounded-field bg-secondary px-2.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
      >
        <Palette className="size-4" />
        <span className="size-3.5 rounded-chip ring-1 ring-border" style={{ background: current.color }} />
      </button>

      {open && (
        <div className="absolute right-0 top-10 z-50 w-60 rounded-card bg-card p-3 shadow-lg">
          <div className="mb-2 px-1 txt-label text-muted-foreground">{t("theme.style")}</div>
          <div className={segmentedTrackClass("mb-3 flex w-full")} role="radiogroup" aria-label={t("theme.style")}>
            {STYLES.map((st) => {
              const active = st.key === styleKey;
              return (
                <button
                  key={st.key}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setStyle(st.key)}
                  className={segmentedItemClass(active, "flex-1 px-2 py-1 text-sm")}
                >
                  {t(st.labelKey)}
                </button>
              );
            })}
          </div>
          <div className="mb-2 px-1 txt-label text-muted-foreground">{t("theme.label")}</div>
          <div className="grid grid-cols-5 gap-2">
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
                    "flex aspect-square items-center justify-center rounded-card ring-1 ring-border transition-transform hover:scale-105",
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
