import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium",
  {
    variants: {
      tone: {
        // 底色统一用 --*-tint、文字统一用 --*-ink（两者成对定义在 globals.css，
        // 明暗两态各自给值）。此前是就地 color-mix + 直接拿语义色当文字色，
        // 结果 #12b76a 落在浅绿底上只有 ~2.8:1，小字过不了 WCAG AA。
        default: "bg-[color-mix(in_oklch,var(--primary)_14%,transparent)] text-[var(--primary)]",
        success: "bg-success-tint text-success-ink",
        warning: "bg-warning-tint text-warning-ink",
        danger: "bg-destructive-tint text-destructive-ink",
        info: "bg-info-tint text-info-ink",
        muted: "bg-muted text-muted-foreground",
        outline: "bg-secondary text-secondary-foreground",
      },
    },
    defaultVariants: { tone: "default" },
  },
);

export function Badge({
  className, tone, ...props
}: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
