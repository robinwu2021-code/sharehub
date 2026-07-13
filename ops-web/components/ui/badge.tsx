import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium",
  {
    variants: {
      tone: {
        default: "bg-[color-mix(in_oklch,var(--primary)_16%,transparent)] text-[var(--primary)]",
        success: "bg-[color-mix(in_oklch,var(--success)_18%,transparent)] text-[var(--success)]",
        warning: "bg-[color-mix(in_oklch,var(--warning)_22%,transparent)] text-[var(--warning)]",
        danger: "bg-[color-mix(in_oklch,var(--destructive)_16%,transparent)] text-[var(--destructive)]",
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
