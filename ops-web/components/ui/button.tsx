import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  // 药丸形：C 端的主 CTA / 分段控件 / 搜索框实测都是 9999px 圆角，这是它"软"的
  // 主要来源，比配色更决定风格。小尺寸下就是 chip，密集表格里也立得住。
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full text-sm font-bold tracking-[0.1px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground shadow-[var(--card-shadow)] hover:opacity-90",
        outline: "bg-secondary text-secondary-foreground hover:bg-accent hover:text-accent-foreground",
        secondary: "bg-secondary text-secondary-foreground hover:bg-accent",
        ghost: "hover:bg-accent hover:text-accent-foreground",
        destructive: "bg-destructive text-white hover:opacity-90",
      },
      size: {
        default: "h-9 px-4 py-2",
        sm: "h-8 px-3.5 text-xs",
        lg: "h-10 px-6",
        icon: "h-9 w-9",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
  /**
   * 提交中：转圈 + 自动 disabled + `aria-busy`，且**按钮宽度不变**。
   *
   * 为什么需要：实测保存/派单/导出这类按钮点下去后毫无反馈，用户会连点 —— 而这些是写操作。
   * 各页只能自己 `disabled={saving}` 并把文案换成「保存中…」，于是按钮宽度当场跳一下、
   * 旁边的按钮跟着位移，第二次点很容易点到别的按钮上。
   *
   * 实现要点：文案**不卸载**，只用 `opacity-0` 隐形（仍占位），spinner 绝对定位居中叠上去。
   * 这样宽度由原文案撑住，切换时零位移。
   */
  loading?: boolean;
}

/** 转圈。用 `animate-spin` + `currentColor`，随按钮变体自动取色。 */
function Spinner() {
  return (
    <svg
      className="absolute size-4 animate-spin"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" className="opacity-25" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, loading = false, disabled, children, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    // asChild 时渲染的是调用方的元素（可能是 <a>），套 spinner 会破坏它的单子节点约定，
    // 故 loading 只在原生 button 上生效；asChild + loading 属误用，直接透传。
    if (asChild || !loading) {
      return (
        <Comp
          ref={ref}
          className={cn(buttonVariants({ variant, size, className }))}
          disabled={asChild ? undefined : disabled}
          {...props}
        >
          {children}
        </Comp>
      );
    }
    return (
      <button
        ref={ref}
        // relative：给绝对定位的 spinner 做定位上下文。
        className={cn(buttonVariants({ variant, size, className }), "relative")}
        disabled
        aria-busy="true"
        {...props}
      >
        <Spinner />
        {/* 文案留在 DOM 里占位（宽度不跳），对屏读器隐藏 —— aria-busy 已表达状态。 */}
        {/* 必须是真实盒（不能 display:contents —— opacity 对它无效），
            内部自带 gap 以复刻按钮原本的图标/文字间距。 */}
        <span className="inline-flex items-center gap-2 opacity-0" aria-hidden="true">{children}</span>
      </button>
    );
  },
);
Button.displayName = "Button";
export { buttonVariants };
