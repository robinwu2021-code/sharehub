"use client";

// 设备详情的生命周期进度条（设计 §五.3）：高亮当前所处环节，下面一行写「卡在哪 / 下一步谁做」并给去处。
// 环节由存储态 + 出库单 / 点位 / 质检派生（lib/device-lifecycle），不额外请求。
import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  LIFECYCLE_PHASES, STAGE_LABEL, STAGE_TONE, lifecyclePhase, lifecycleStage, nextStep,
} from "@/lib/device-lifecycle";
import type { Cabinet } from "@/lib/types";

export function LifecycleBar({ cabinet }: { cabinet: Cabinet }) {
  const stage = lifecycleStage(cabinet);
  const phase = lifecyclePhase(stage);
  const at = LIFECYCLE_PHASES.findIndex((p) => p.key === phase);
  const next = nextStep(cabinet);
  return (
    <Card>
      <CardContent className="space-y-3 pt-5">
        <ol className="flex flex-wrap items-center gap-1.5" aria-label="设备生命周期">
          {LIFECYCLE_PHASES.map((p, i) => (
            <li key={p.key} className="flex items-center gap-1.5">
              <span
                aria-current={i === at ? "step" : undefined}
                className={cn(
                  "rounded-chip px-2 py-0.5 txt-caption",
                  i === at ? "bg-primary text-primary-foreground" : i < at ? "bg-muted text-foreground" : "text-muted-foreground",
                )}
              >
                {p.label}
              </span>
              {i < LIFECYCLE_PHASES.length - 1 && <span className="text-muted-foreground">›</span>}
            </li>
          ))}
        </ol>
        <div className="flex flex-wrap items-center gap-2 txt-body">
          <Badge tone={STAGE_TONE[stage]}>{STAGE_LABEL[stage]}</Badge>
          <span>{next.text}</span>
          <span className="txt-caption text-muted-foreground">· {next.who}</span>
          {next.href && <Link href={next.href} className="txt-caption text-primary hover:underline">去处理 →</Link>}
        </div>
      </CardContent>
    </Card>
  );
}
