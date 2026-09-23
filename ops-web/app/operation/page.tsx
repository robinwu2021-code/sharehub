"use client";

// /operation 入口：跳到当前角色在运营管理下第一个可打开的页面。
// 不写死 /operation/overview —— 例如 CS 只看得到「问题管理」「公告管理」，
// 写死会把它送进一个自己没权限的页面。
import { useEffect } from "react";
import { useViewer } from "@/lib/hooks/use-viewer";
import { useRouter } from "next/navigation";
import { NAV, sectionDefaultHref } from "@/lib/nav";
import { useAuth } from "@/lib/auth";

export default function OperationIndex() {
  const router = useRouter();
  const role = useAuth((s) => s.role);
  const viewer = useViewer();
  useEffect(() => {
    const section = NAV.find((s) => s.key === "operation");
    if (section) router.replace(sectionDefaultHref(section, viewer));
  }, [role, router]);
  return null;
}
