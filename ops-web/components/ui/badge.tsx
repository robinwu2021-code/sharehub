import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  // 形态取自 C 端 pb-tag：药丸 + 字重 700 + 11px（原为 rounded-md/500，偏"方"）
  // 12px/600：11px + 700 看着"更醒目"，实际笔画在小字号下糊成一团反而更难认。
  // 规范定的字号下限就是 12px。
  "inline-flex items-center rounded-chip px-2.5 py-0.5 text-[12px] font-semibold leading-[1.5]",
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

/**
 * 徽标色调的唯一真源。
 * 各页的状态映射表（`StatusMap<T>`，见 ./status-badge）都引这个类型，
 * 不要再就地写 `"muted" | "warning" | "danger"` 之类的字面量联合 ——
 * 那样每加一个色调就要改十几处，而且各处允许的子集互不相同、读不出规律。
 */
export type BadgeTone = NonNullable<VariantProps<typeof badgeVariants>["tone"]>;

export function Badge({
  className, tone, ...props
}: React.HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
