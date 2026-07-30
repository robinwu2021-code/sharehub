"use client";

// /dev/ui 这页自己的骨架件。刻意不放进 components/ —— 它们只服务这张工具页，
// 进了 components/ 就成了"要维护的公共资产"。
//
// 约定：**真正的组件实例一律包在 <Specimen> 里**（它落 data-specimen 属性），
// 规范体检只扫这些容器内部。骨架自身不参与扫描。
import * as React from "react";
import { cn } from "@/lib/utils";

export type Layer = "原语" | "组合件" | "业务件";

const LAYER_STYLE: Record<Layer, string> = {
  原语: "bg-info-tint text-info-ink",
  组合件: "bg-warning-tint text-warning-ink",
  业务件: "bg-success-tint text-success-ink",
};

/** 一个组件一个区块。name 会成为体检清单里的归属名，务必写组件的导出名。 */
export function Section({
  id, name, layer, file, purpose, children,
}: {
  id: string;
  name: string;
  layer: Layer;
  file: string;
  purpose: string;
  children: React.ReactNode;
}) {
  return (
    <section
      id={id}
      data-comp={name}
      className="scroll-mt-24 rounded-card bg-card p-5 shadow-[var(--card-shadow)]"
    >
      <header className="mb-4">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-[15px] font-extrabold tracking-[-0.2px]">{name}</h2>
          <span className={cn("rounded-chip px-2 py-0.5 text-[11px] font-bold", LAYER_STYLE[layer])}>{layer}</span>
          <code className="rounded-chip bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">{file}</code>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">{purpose}</p>
      </header>
      <div className="space-y-4">{children}</div>
    </section>
  );
}

/** 状态行：左侧写状态名，右侧是真组件。`note` 用来写"这个状态怎么触发/为什么长这样"。 */
export function Row({
  label, note, children, stack, className,
}: {
  label: string;
  note?: React.ReactNode;
  children: React.ReactNode;
  /** 组件较宽（表格/抽屉内容）时纵向排 */
  stack?: boolean;
  className?: string;
}) {
  return (
    <div className={cn("grid gap-2", stack ? "grid-cols-1" : "md:grid-cols-[160px_1fr]", className)}>
      <div className="pt-1">
        <div className="text-xs font-bold">{label}</div>
        {note && <div className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{note}</div>}
      </div>
      <Specimen className={stack ? "min-w-0 flex-col items-stretch gap-3" : undefined}>{children}</Specimen>
    </div>
  );
}

/** 组件实例容器。规范体检的扫描边界。 */
export function Specimen({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div data-specimen className={cn("flex flex-wrap items-center gap-2", className)}>
      {children}
    </div>
  );
}

/**
 * 不合规标注。这页的核心价值之一：**照原样展示现状，旁边写清哪不对**。
 * 不要为了让页面"好看"去改组件（那是 P2 的活），也不要把问题藏起来。
 */
export function Flaw({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-field bg-destructive-tint px-3 py-2 text-[11px] leading-relaxed text-destructive-ink">
      <span className="font-bold">不合规 · </span>
      {children}
    </div>
  );
}

/** 中性说明（不是缺陷，只是解释）。 */
export function Hint({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-field bg-muted px-3 py-2 text-[11px] leading-relaxed text-muted-foreground">
      {children}
    </div>
  );
}

/** 缺失能力标注：组件**还没有**这个状态（如 Button 无 loading）。 */
export function Missing({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-field bg-warning-tint px-3 py-2 text-[11px] leading-relaxed text-warning-ink">
      <span className="font-bold">缺能力 · </span>
      {children}
    </div>
  );
}

/** 小标签（用于在一行里标出 variant/size 名） */
export function Tag({ children }: { children: React.ReactNode }) {
  return <span className="text-[10px] font-medium text-muted-foreground">{children}</span>;
}

/** 竖排「标签 + 组件」的小格子，用于 variant × size 这类矩阵 */
export function Cell({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col items-start gap-1">
      <Tag>{label}</Tag>
      {children}
    </div>
  );
}
