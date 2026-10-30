"use client";

// 窄屏导航（< md）。Rail 与二级面板在 md 以下整块隐藏（rail.tsx 的 `hidden md:flex`），
// 而在此之前窄屏没有任何替代 —— 商家用手机打开门户（需求方案 §九）只能停在落地页，靠手敲网址换页。
// 这里不另造菜单：分区与叶子取自同一套 visibleSections / visibleLeaves，权限与分期门禁与桌面一致。
import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Menu } from "lucide-react";
import { Drawer } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { useViewer } from "@/lib/hooks/use-viewer";
import { useI18n } from "@/lib/i18n";
import { visibleSections, visibleLeaves, isLeafLocked, normPath } from "@/lib/nav";
import { cn } from "@/lib/utils";

export function MobileNav() {
  const [open, setOpen] = useState(false);
  const viewer = useViewer();
  const pathname = normPath(usePathname());
  const { t, tNavNode } = useI18n();
  const sections = visibleSections(viewer);

  return (
    <div className="md:hidden">
      <Button size="sm" variant="ghost" aria-label={t("common.menu")} title={t("common.menu")} onClick={() => setOpen(true)}>
        <Menu className="size-5" />
      </Button>
      <Drawer open={open} onOpenChange={setOpen} title={t("common.appName")} width="w-[85vw] max-w-[320px]">
        <nav className="space-y-4">
          {sections.map((s) => {
            const leaves = visibleLeaves(s, viewer).filter((l) => !isLeafLocked(l));
            if (leaves.length === 0) return null;
            return (
              <div key={s.key}>
                <p className="mb-1 txt-caption text-muted-foreground">{tNavNode(s)}</p>
                <ul className="space-y-0.5">
                  {leaves.map((l) => {
                    const active = normPath(l.href.split("?")[0]) === pathname;
                    return (
                      <li key={l.href}>
                        <Link href={l.href} onClick={() => setOpen(false)}
                          className={cn("block rounded-field px-3 py-2 txt-body",
                            active ? "bg-accent text-[var(--primary-text)]" : "hover:bg-accent/60")}>
                          {tNavNode(l)}
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              </div>
            );
          })}
        </nav>
      </Drawer>
    </div>
  );
}
