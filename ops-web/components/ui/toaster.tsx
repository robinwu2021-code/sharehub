"use client";

// 全局 toast 容器（挂在 Providers 里）。扁平色块风格，RTL 下自动靠对侧（用 inset-inline-end）。
import { CircleCheck, CircleX, Info, X } from "lucide-react";
import { useToasts } from "@/lib/notify";
import { cn } from "@/lib/utils";

const ICON = { success: CircleCheck, error: CircleX, info: Info };
const TONE = {
  success: "bg-[color-mix(in_oklch,var(--success)_16%,var(--card))] text-[var(--success)]",
  error: "bg-[color-mix(in_oklch,var(--destructive)_14%,var(--card))] text-[var(--destructive)]",
  info: "bg-secondary text-foreground",
};

export function Toaster() {
  const { toasts, dismiss } = useToasts();
  if (!toasts.length) return null;
  return (
    <div className="pointer-events-none fixed bottom-5 z-[var(--z-toast)] flex flex-col gap-2" style={{ insetInlineEnd: "1.25rem" }}>
      {toasts.map((t) => {
        const Icon = ICON[t.type];
        return (
          <div
            key={t.id}
            className={cn(
              "pointer-events-auto flex items-center gap-2.5 rounded-card px-4 py-2.5 txt-body shadow-[var(--card-shadow)]",
              TONE[t.type],
            )}
          >
            <Icon className="size-4 shrink-0" />
            <span className="max-w-[320px]">{t.message}</span>
            <button
              onClick={() => dismiss(t.id)}
              className="ml-1 rounded-control opacity-60 hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]"
              aria-label="关闭"
            >
              <X className="size-3.5" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
