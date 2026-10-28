"use client";

// /operation 入口：跳到当前角色能打开的第一个 /operation/* 页面。
// 2026-10-28「运营管理」L1 撤销（OM11）：/operation 下剩的五页（银行 / 品牌 / 问题 / 应用版本 / 公告）
// 归系统管理 › 基础资料；站点、计费、分成等页已由各自的新 L1 认领。旧书签停在 /operation 时按这里跳。
// 不写死某一页 —— 例如 CS 只看得到「问题管理」「公告管理」，写死会把它送进一个自己没权限的页面。
import { useEffect } from "react";
import { useViewer } from "@/lib/hooks/use-viewer";
import { useRouter } from "next/navigation";
import { NAV, visibleLeaves, isLeafLocked, sectionDefaultHref } from "@/lib/nav";
import { useAuth } from "@/lib/auth";

export default function OperationIndex() {
  const router = useRouter();
  const role = useAuth((s) => s.role);
  const viewer = useViewer();
  useEffect(() => {
    const section = NAV.find((s) => s.key === "system");
    if (!section) return;
    const leaf = visibleLeaves(section, viewer).find((l) => l.href.startsWith("/operation/") && !l.soon && !isLeafLocked(l));
    router.replace(leaf?.href ?? sectionDefaultHref(section, viewer));
  }, [role, router]);
  return null;
}
