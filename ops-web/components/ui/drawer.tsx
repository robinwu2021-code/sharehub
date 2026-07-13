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

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-4">
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className="text-sm">{children}</div>
    </div>
  );
}
