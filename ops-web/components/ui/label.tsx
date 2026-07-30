"use client";

// 表单标签原语。
//
// **为什么需要它**：`@radix-ui/react-label` 早就装进了 package.json，却**没有任何调用点**
// —— 说明当初打算做、做漏了。后果是页面里的标签全是裸 `<div className="text-xs …">`：
// 点标签不聚焦控件、屏读器读不到「这个输入框叫什么」（`Input` 只有 placeholder），
// 且字号字重每处不一。
//
// **为什么用 Radix 而不是原生 `<label>`**：Radix 版额外阻止了双击标签时的文本选中
// （原生行为会选中标签文字，在表单里连点两次就会出现一片蓝，很脏），其余等价。
//
// **与 `Field`（`ui/drawer.tsx`）的边界**：
// - `Field` 是**只读详情行**（标签 + 值），没有可聚焦控件，不该用 `Label`（无 `for` 目标）。
// - `Label` 用于**可编辑控件**的标签，必须给 `htmlFor` 或把控件套在里面。
// - `FormDrawer` 的 `FieldRow` 是表单行，内部标签应换成 `Label`（P2）。
import * as React from "react";
import * as LabelPrimitive from "@radix-ui/react-label";
import { cn } from "@/lib/utils";

export interface LabelProps extends React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root> {
  /** 必填星号。放在文案**末尾侧**（`me-`/`ms-` 逻辑方向，RTL 下自动到左边）。 */
  required?: boolean;
}

export const Label = React.forwardRef<React.ComponentRef<typeof LabelPrimitive.Root>, LabelProps>(
  ({ className, required, children, ...props }, ref) => (
    <LabelPrimitive.Root
      ref={ref}
      className={cn(
        "inline-block text-xs font-bold text-muted-foreground",
        // 控件 disabled 时标签同步变淡：靠 peer 关系（<Label className="peer-disabled:…"> 需
        // 控件在前）不可靠，故这里用 group：把行套 `group` 并给控件 `data-disabled`。
        "group-data-[disabled=true]:opacity-50",
        className,
      )}
      {...props}
    >
      {children}
      {required && <span className="ms-0.5 text-destructive" aria-hidden="true">*</span>}
    </LabelPrimitive.Root>
  ),
);
Label.displayName = "Label";
