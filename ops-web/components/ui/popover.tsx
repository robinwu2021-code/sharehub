"use client";

// 轻量浮层原语。
//
// **为什么需要它**：`MultiSelect` / `FilterSelect` / `DateInput` 各自手写了一个
// 「点一下弹一块面板」，各自处理外点关闭、Esc、定位 —— 而定位全是 `absolute` 挂在触发器上，
// 这正是 `ui/tooltip.tsx` 顶部记下的那个坑（触发器在 `overflow-y-auto` 容器里时浮层被裁掉）。
// Radix 的 Popover 走 Portal + Floating-UI 定位，顺带给了翻转/贴边收敛与焦点归还。
//
// **三个浮层的边界（选错就是重复实现）**：
// | 场景 | 用 |
// |---|---|
// | 一句纯说明文字，hover 即出，不可交互 | `Tooltip` |
// | 少量**动作列表**（编辑/停用/删除），要方向键与 typeahead | `DropdownMenu` |
// | 任意内容：筛选面板、迷你表单、二维码、图表说明，需要可点可输入 | `Popover` |
// | 要占据视线、内容成体系、需要标题与底部动作条 | `Drawer` / `ConfirmDialog` |
//
// 判据一句话：**Popover 是"顺手看一眼/改一下"，Drawer 是"进去做一件事"。**
// 抽屉会遮住列表上下文，一旦用户需要边看列表边改，就该是 Popover。
import * as React from "react";
import * as PopoverPrimitive from "@radix-ui/react-popover";
import { cn } from "@/lib/utils";

export const Popover = PopoverPrimitive.Root;
export const PopoverTrigger = PopoverPrimitive.Trigger;
/** 关闭按钮：套在内容里的「取消/完成」上（`asChild`）。 */
export const PopoverClose = PopoverPrimitive.Close;
/** 显式锚点：触发器与浮层要对齐的目标不是同一个元素时用（如整行触发、对齐某个单元格）。 */
export const PopoverAnchor = PopoverPrimitive.Anchor;

export const PopoverContent = React.forwardRef<
  React.ComponentRef<typeof PopoverPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>
>(({ className, align = "start", sideOffset = 6, ...props }, ref) => (
  <PopoverPrimitive.Portal>
    <PopoverPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      className={cn(
        // card 档圆角（浮层不是大面，sheet 20px 会显得肿）+ 浮层专属阴影 + 层级 token。
        // 面色走 card（项目没有独立的 popover token；浮层与卡片同为 --pb-elev 一档）。
        "z-[var(--z-popover)] min-w-[200px] rounded-card bg-card p-3 text-card-foreground shadow-pop outline-none",
        className,
      )}
      {...props}
    />
  </PopoverPrimitive.Portal>
));
PopoverContent.displayName = "PopoverContent";
