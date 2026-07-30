// 分段控件（segmented）的形状与配色，供 `ui/tabs.tsx`（页内维度切换）与
// `ui/tab-header.tsx`（页内 tab 条里的分段控件）共用。
//
// 两者场景不同、不合并成同一个组件（一个是纯 UI 层的内容切换，一个挂着 URL 深链/
// 面包屑/分期屏蔽等页面级导航语义），但视觉形态——灰槽 + 全圆 + 选中项白色药丸——
// 应该是同一份规格，所以把 className 拼装抽成这两个函数，具体尺寸（字号/内边距/
// 是否换行）各自按自己的场景传 className 覆盖。规格取自「设计规范-运营端配色与
// 形状.md」：灰槽 bg-secondary + 全圆 rounded-chip + 选中项白色药丸 + 字重 600/700。
import { cn } from "@/lib/utils";

/** 分段控件的灰槽容器。 */
export function segmentedTrackClass(className?: string) {
  return cn("inline-flex gap-1 rounded-chip bg-secondary p-1", className);
}

/** 分段控件里的单个选项按钮；`active` 决定是否渲染成白色药丸。 */
export function segmentedItemClass(active: boolean, className?: string) {
  return cn(
    "rounded-chip whitespace-nowrap transition-colors duration-[var(--dur)] ease-[var(--ease)]",
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[--ring-offset-bg]",
    active
      ? "bg-card font-semibold text-foreground shadow-[var(--card-shadow)]"
      : "text-muted-foreground hover:text-foreground",
    className,
  );
}
