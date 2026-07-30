"use client";

// 下拉动作菜单原语 + 表格操作列组合件 `RowActions`。
//
// **为什么需要它**：实测列表页的「操作」列把 3~5 个按钮**平铺**在一格里
// （详情 / 编辑 / 停用 / 归档 / 派单…）。两个后果：
//  1. 列一窄就换行，整行行高被撑高，密集表格的密度优势全丢；
//  2. 所有动作视觉权重相同 —— 「归档」和「详情」一样醒目，误点成本不对称。
// 解法是「主动作留在外面 + 次要/危险动作收进『更多』」，这需要一个真正的菜单原语。
//
// **键盘行为一律交给 Radix**（上下键、Home/End、typeahead 首字母跳转、Esc 关闭、
// 关闭后焦点归还触发器、`role="menu"/"menuitem"` 与 `aria-expanded`）。
// 这些自绘必漏，且漏了在纯鼠标测试里看不出来 —— 明确不自绘。
//
// **与 `Popover` 的边界**：菜单里只放**动作**（点了就发生某件事）。
// 需要输入框、复选清单、图表、说明文字的浮层用 `Popover`；
// 需要占据视线、有标题与底部动作条的用 `Drawer`。
import * as React from "react";
import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import { MoreHorizontal } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

export const DropdownMenu = DropdownMenuPrimitive.Root;
export const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;
export const DropdownMenuGroup = DropdownMenuPrimitive.Group;

export const DropdownMenuContent = React.forwardRef<
  React.ComponentRef<typeof DropdownMenuPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>
>(({ className, align = "end", sideOffset = 6, ...props }, ref) => (
  <DropdownMenuPrimitive.Portal>
    <DropdownMenuPrimitive.Content
      ref={ref}
      align={align}
      sideOffset={sideOffset}
      className={cn(
        "z-[var(--z-popover)] min-w-[160px] overflow-hidden rounded-card bg-card p-1 text-card-foreground shadow-pop outline-none",
        className,
      )}
      {...props}
    />
  </DropdownMenuPrimitive.Portal>
));
DropdownMenuContent.displayName = "DropdownMenuContent";

export interface DropdownMenuItemProps
  extends React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item> {
  /** 危险动作（删除/归档/强制解绑）：文字与图标转 destructive。 */
  danger?: boolean;
}

export const DropdownMenuItem = React.forwardRef<
  React.ComponentRef<typeof DropdownMenuPrimitive.Item>,
  DropdownMenuItemProps
>(({ className, danger, ...props }, ref) => (
  <DropdownMenuPrimitive.Item
    ref={ref}
    className={cn(
      // 菜单项在浮层内部，圆角收一档到 field；起始侧对齐文字，图标用 gap 不用 mr。
      "flex cursor-pointer select-none items-center gap-2 rounded-field px-2.5 py-1.5 text-sm outline-none",
      "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)]",
      // 键盘高亮与鼠标 hover 走同一个态：Radix 的 data-highlighted 覆盖两者。
      "data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground",
      "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
      danger && "text-destructive data-[highlighted]:bg-destructive-tint data-[highlighted]:text-destructive-ink",
      "[&_svg]:size-4 [&_svg]:shrink-0",
      className,
    )}
    {...props}
  />
));
DropdownMenuItem.displayName = "DropdownMenuItem";

export const DropdownMenuLabel = React.forwardRef<
  React.ComponentRef<typeof DropdownMenuPrimitive.Label>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Label
    ref={ref}
    className={cn("px-2.5 py-1.5 text-xs font-bold text-muted-foreground", className)}
    {...props}
  />
));
DropdownMenuLabel.displayName = "DropdownMenuLabel";

export const DropdownMenuSeparator = React.forwardRef<
  React.ComponentRef<typeof DropdownMenuPrimitive.Separator>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Separator>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Separator ref={ref} className={cn("-mx-1 my-1 h-px bg-border", className)} {...props} />
));
DropdownMenuSeparator.displayName = "DropdownMenuSeparator";

/* ────────────────────────────────────────────────────────────────────────────
   组合件：表格操作列
   ──────────────────────────────────────────────────────────────────────────── */

export interface RowAction {
  /** 菜单项文案。业务文案由调用方传（原语不认业务词，也不替调用方查 i18n）。 */
  label: React.ReactNode;
  onSelect: () => void;
  icon?: React.ReactNode;
  disabled?: boolean;
  /** 危险动作：转 destructive 色，并自动排到分隔线之下。 */
  danger?: boolean;
  /** 无权限等原因导致不可用时的说明。会渲染成 `title`，鼠标停留可见。 */
  hint?: string;
}

/**
 * 「更多」按钮 + 动作菜单。表格操作列用。
 *
 * 用法（主动作仍留在外面，只把次要动作交给它）：
 * ```tsx
 * cell: (r) => (
 *   <div className="flex items-center gap-2">
 *     <Button size="sm" variant="ghost" onClick={() => open(r)}>详情</Button>
 *     <RowActions actions={[
 *       { label: "编辑", onSelect: () => edit(r) },
 *       { label: "归档", onSelect: () => archive(r), danger: true },
 *     ]} />
 *   </div>
 * )
 * ```
 *
 * - `actions` 全空（或全被过滤掉）时**整个按钮不渲染** —— 权限降级后留一个空的
 *   「更多」按钮，点开是空浮层，比不显示更糟。
 * - `danger` 项自动收到底部并以分隔线隔开：危险动作不该与普通动作紧邻，
 *   方向键连按容易多走一格。
 */
export function RowActions({
  actions, label, className, align = "end",
}: {
  actions: (RowAction | false | null | undefined)[];
  /** 按钮的无障碍名，默认取 i18n `common.more`。 */
  label?: string;
  className?: string;
  align?: "start" | "center" | "end";
}) {
  const { t } = useI18n();
  const items = actions.filter(Boolean) as RowAction[];
  if (items.length === 0) return null;

  const normal = items.filter((a) => !a.danger);
  const danger = items.filter((a) => a.danger);
  const a11yLabel = label ?? t("common.more");

  const renderItem = (a: RowAction, i: number) => (
    <DropdownMenuItem
      key={i}
      danger={a.danger}
      disabled={a.disabled}
      title={a.hint}
      // Radix 的 onSelect 带 event；对外只暴露无参回调（README：回调给值不给 event）。
      onSelect={() => a.onSelect()}
    >
      {a.icon}
      {a.label}
    </DropdownMenuItem>
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={a11yLabel}
        title={a11yLabel}
        className={cn(
          "inline-flex size-8 items-center justify-center rounded-chip text-muted-foreground",
          "transition-colors duration-[var(--dur-fast)] ease-[var(--ease)] hover:bg-accent hover:text-accent-foreground",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
          "data-[state=open]:bg-accent data-[state=open]:text-accent-foreground",
          "disabled:pointer-events-none disabled:opacity-50",
          className,
        )}
        // 行整体常挂 onClick（打开详情），菜单点击不能顺带触发行点击。
        onClick={(e) => e.stopPropagation()}
      >
        <MoreHorizontal className="size-4" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align={align}>
        {normal.map(renderItem)}
        {normal.length > 0 && danger.length > 0 && <DropdownMenuSeparator />}
        {danger.map((a, i) => renderItem(a, normal.length + i))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
