"use client";

import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

// 右侧抽屉（详情/表单）。基于 radix dialog。
export function Drawer({
  open, onOpenChange, title, desc, children, footer, width = "w-[440px]",
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  desc?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  width?: string;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/40 data-[state=open]:animate-in data-[state=open]:fade-in" />
        <Dialog.Content
          className={cn(
            "fixed right-0 top-0 z-50 flex h-screen flex-col bg-card shadow-xl outline-none",
            "data-[state=open]:animate-in data-[state=open]:slide-in-from-right",
            width,
          )}
        >
          <div className="flex items-start justify-between bg-muted/50 p-5">
            <div>
              <Dialog.Title className="text-base font-medium">{title}</Dialog.Title>
              {desc && <Dialog.Description className="mt-0.5 text-sm text-muted-foreground">{desc}</Dialog.Description>}
            </div>
            <Dialog.Close className="rounded-md p-1 text-muted-foreground hover:bg-accent">
              <X className="size-4" />
            </Dialog.Close>
          </div>
          <div className="flex-1 overflow-y-auto p-5">{children}</div>
          {footer && <div className="flex justify-end gap-2 bg-muted/40 p-4">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/**
 * 详情行：标签在上、值在下。抽屉详情与卡片栅格共用这一份（原先散了 3 份定义）。
 *
 * - 默认带 `mb-4`（抽屉里靠自身间距堆叠）；
 * - 放进 grid/flex 由容器给 gap 时传 `className="mb-0"`（cn 走 tailwind-merge，会覆盖掉 mb-4）。
 *
 * 与 FormDrawer 的 FieldRow **不是**同一件事：那个带必填星号、字数计数、错误态与控件，
 * 是表单行；这个是只读展示行。共同点只有外框间距，合并会把表单关注点塞进展示件。
 */
export function Field({
  label, children, className,
}: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("mb-4", className)}>
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className="text-sm">{children}</div>
    </div>
  );
}
