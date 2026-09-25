"use client";

// 文件「查看」入口：点的那一刻才取限时地址（api.fileUrl），不把签名地址存进 state ——
// 它会过期，留着就是一个点不开的链接。
import { useState } from "react";
import { FileText } from "lucide-react";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { cn } from "@/lib/utils";

export function FileLink({ fileNo, label, className }: { fileNo: string; label?: string; className?: string }) {
  const [busy, setBusy] = useState(false);
  const open = async () => {
    setBusy(true);
    try {
      const r = await api.fileUrl(fileNo);
      window.open(r.url, "_blank", "noopener,noreferrer");
    } catch {
      notify.error(`文件 ${fileNo} 取不到下载地址（可能已被清理或无权查看）`);
    } finally {
      setBusy(false);
    }
  };
  return (
    <button
      type="button"
      onClick={open}
      disabled={busy}
      className={cn("inline-flex items-center gap-1 txt-caption text-primary underline-offset-2 hover:underline disabled:opacity-50", className)}
    >
      <FileText className="size-3.5" />
      {label ?? fileNo}
    </button>
  );
}
