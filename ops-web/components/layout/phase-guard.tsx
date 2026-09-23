"use client";

// 页面级分级兜底：URL 直达「当前分级未解锁」功能时，拦截并提示，不渲染真实页面。
// 集中在 AppShell 的 main 内，避免逐页包裹。可访问则透传 children。
import { usePathname, useSearchParams } from "next/navigation";
import { useViewer } from "@/lib/hooks/use-viewer";
import Link from "next/link";
import { Lock } from "lucide-react";
import { routeLockedPhase } from "@/lib/nav";
import { PHASE_NAME, CURRENT_PHASE } from "@/lib/phase";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

export function PhaseGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const sp = useSearchParams();
  const viewer = useViewer();
  const { t } = useI18n();

  const locked = routeLockedPhase(pathname, sp.get("tab"), sp.get("view"), viewer);
  if (!locked) return <>{children}</>;

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <div className="mb-4 flex size-14 items-center justify-center rounded-sheet bg-primary/8 text-primary/70">
        <Lock className="size-6" />
      </div>
      <div className="mb-1 txt-title">{t("phase.lockedTitle")}</div>
      <p className="max-w-md txt-body text-muted-foreground">
        {t("phase.lockedDesc", {
          feature: `${PHASE_NAME[locked]} ${t("phase.suffix")}`,
          phase: PHASE_NAME[locked],
          current: PHASE_NAME[CURRENT_PHASE],
        })}
      </p>
      <Link
        href="/"
        className="mt-5 rounded-field bg-secondary px-4 py-2 txt-strong text-foreground transition-colors hover:bg-accent"
      >
        {t("phase.backHome")}
      </Link>
    </div>
  );
}
