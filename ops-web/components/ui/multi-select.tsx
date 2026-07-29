"use client";

// 轻量多选（不引第三方库）：触发区展示已选 chips，点开下拉勾选。
// 值统一为 string[]；csv 形态由调用方（FormDrawer）用 csvToArray/arrayToCsv 转换。
// RTL 友好：只用 ms-*/me-*/ps-*/pe-* 逻辑属性。
import * as React from "react";
import { Check, ChevronDown, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

export type Option = { value: string; label: string };

export function MultiSelect({
  value, options, onChange, disabled, placeholder, invalid, onBlur, className,
}: {
  value: string[];
  options: Option[];
  onChange: (v: string[]) => void;
  disabled?: boolean;
  placeholder?: string;
  invalid?: boolean;
  /** 关闭下拉时触发，供 FormDrawer 做 blur 校验。 */
  onBlur?: () => void;
  className?: string;
}) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(false);
  const rootRef = React.useRef<HTMLDivElement>(null);

  // 关闭时触发 onBlur（供 FormDrawer 做失焦校验）。
  // ⚠️ onBlur 绝不能写在 setOpen 的 updater 里：updater 会在 render 阶段执行，
  // 在其中调用父组件的 setState 会报 "Cannot update a component while rendering
  // a different component"，且 StrictMode 下 updater 执行两次会重复触发。
  // 用 ref 读当前 open 值，副作用留在事件回调里。（同类错误另见 confirm-dialog 的 resolve）
  const openRef = React.useRef(false);
  React.useEffect(() => { openRef.current = open; }, [open]);

  const close = React.useCallback(() => {
    const wasOpen = openRef.current;
    setOpen(false);
    if (wasOpen) onBlur?.();
  }, [onBlur]);

  React.useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.stopPropagation(); close(); }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [open, close]);

  const labelOf = (v: string) => options.find((o) => o.value === v)?.label ?? v;
  const toggle = (v: string) => onChange(value.includes(v) ? value.filter((x) => x !== v) : [...value, v]);

  return (
    <div ref={rootRef} className={cn("relative", className)}>
      <button
        type="button"
        disabled={disabled}
        aria-expanded={open}
        onClick={() => (open ? close() : setOpen(true))}
        className={cn(
          "flex min-h-9 w-full items-center gap-1 rounded-lg bg-secondary px-2.5 py-1 text-start text-sm transition-colors",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50",
          invalid && "ring-2 ring-destructive",
        )}
      >
        <span className="flex flex-1 flex-wrap gap-1">
          {value.length === 0 ? (
            <span className="text-muted-foreground">{placeholder ?? t("form.selectPlaceholder")}</span>
          ) : (
            value.map((v) => (
              <span key={v} className="inline-flex items-center gap-1 rounded bg-card px-1.5 text-xs leading-5">
                {labelOf(v)}
                {!disabled && (
                  <X
                    className="size-3 cursor-pointer text-muted-foreground hover:text-foreground"
                    onClick={(e) => { e.stopPropagation(); onChange(value.filter((x) => x !== v)); }}
                  />
                )}
              </span>
            ))
          )}
        </span>
        <ChevronDown className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")} />
      </button>

      {open && !disabled && (
        <div className="absolute inset-x-0 top-full z-50 mt-1 max-h-56 overflow-y-auto rounded-lg bg-card p-1 shadow-[var(--card-shadow)] ring-1 ring-black/5">
          {options.length === 0 && (
            <div className="px-2 py-1.5 text-xs text-muted-foreground">{t("common.empty")}</div>
          )}
          {options.map((o) => {
            const on = value.includes(o.value);
            return (
              <button
                key={o.value}
                type="button"
                onClick={() => toggle(o.value)}
                className={cn(
                  "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-start text-[13px] transition-colors",
                  on ? "bg-accent text-foreground" : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
                )}
              >
                <Check className={cn("size-3.5 shrink-0", on ? "opacity-100" : "opacity-0")} />
                <span className="truncate">{o.label}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
