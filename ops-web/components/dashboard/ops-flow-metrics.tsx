"use client";

// 经营看板 › 运营指标（运营核心流程 G5，`GET /api/ops/ops-flow-metrics`，dashboard:overview:read）。
//
// 每个比率都带分母（「样本 N」）：样本 3 条的 100% 与样本 300 条的 100% 不是一回事，
// 看的人要能自己判断。**null = 分母为 0，显示「无数据」而不是 0%** ——
// 「这 30 天没有合同到期」与「到期的一份都没续」是两回事，写成 0% 会让人去追一个不存在的问题。
//
// 口径解释放在每张卡的 HelpNote 里（不常驻）：每天看同一页的人不需要每次读一遍定义。
// 口径与后端 OpsFlowMetricsService 类注释逐条对应，改一边要改另一边。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/hooks/use-can";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HelpNote } from "@/components/ui/help-note";
import { FilterSelect } from "@/components/ui/filter-select";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import type { OpsFlowMetrics } from "@/lib/types";

const WINDOWS = [
  { value: "7", label: "近 7 天" },
  { value: "30", label: "近 30 天" },
  { value: "90", label: "近 90 天" },
];

const ymd = (d: Date) => {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

const pct = (v: number | null) => (v === null ? null : `${(Math.round(v * 1000) / 10).toFixed(1)}%`);
const days = (v: number | null) => (v === null ? null : `${v} 天`);
const minutes = (v: number | null) => {
  if (v === null) return null;
  if (v < 60) return `${v} 分钟`;
  const h = Math.floor(v / 60);
  return `${h} 小时${v % 60 ? ` ${v % 60} 分` : ""}`;
};

interface Tile {
  key: string;
  label: string;
  value: string | null;
  sample: string;
  help: string;
  /** 目标方向不达标时标 warning（形状 ▼ + 色，不单靠颜色）。只给有公认目标的几项。 */
  bad?: boolean;
}

function tiles(m: OpsFlowMetrics): Tile[] {
  return [
    {
      key: "sla", label: "工单 SLA 达成率", value: pct(m.woSlaRate), sample: `样本 ${m.woWithSla} 张工单`,
      bad: m.woSlaRate !== null && m.woSlaRate < 0.9,
      help: "窗口内创建、已完工且有 SLA 计时的工单里，没有超过解决时限的占比。低于 90% 标 ▼。",
    },
    {
      key: "mttr", label: "故障平均修复时长", value: minutes(m.mttrMinutes), sample: `样本 ${m.faultResolved} 张故障单`,
      help: "MTTR：窗口内创建、已完工的故障维修单，从开单到最后一次处理记录的平均时长。",
    },
    {
      key: "renew", label: "合同续约率", value: pct(m.renewalRate), sample: `到期 ${m.contractsEnded} 份 · 已续 ${m.contractsRenewed} 份`,
      help: "窗口内到期的主合同里，已有续签合同（任意状态，草稿除外）的占比。补充协议不计入。",
    },
    {
      key: "expired", label: "到期未续占比", value: pct(m.expiredNotRenewedRatio), sample: `到期 ${m.contractsEnded} 份`,
      bad: m.expiredNotRenewedRatio !== null && m.expiredNotRenewedRatio > 0.2,
      help: "= 1 − 续约率。到期没续的站点要么在无合同营业（会触发「无合同在营业」告警），要么该撤场。高于 20% 标 ▼。",
    },
    {
      key: "install", label: "装机时效", value: days(m.installLeadDaysAvg), sample: `首次上线 ${m.sitesWentLive} 个站点`,
      help: "窗口内首次上线的站点，从该站点最早一份合同生效到首次上线的平均天数。越短，合同签下后越快开始产生收入。",
    },
    {
      key: "trial", label: "首次试借还通过率", value: pct(m.firstTrialPassRate), sample: `样本 ${m.firstTrials} 台机柜`,
      bad: m.firstTrialPassRate !== null && m.firstTrialPassRate < 0.8,
      help: "窗口内做第一次试借还的机柜里，第一次就通过的占比。低说明装机质量或出厂质检有问题。低于 80% 标 ▼。",
    },
    {
      key: "lead", label: "线索转化率", value: pct(m.leadConversionRate), sample: `新建 ${m.leadsCreated} 条 · 签约 ${m.leadsSigned} 条`,
      help: "窗口内新建的商机里，已走到签约阶段的占比。",
    },
    {
      key: "signing", label: "平均签约周期", value: days(m.signingCycleDaysAvg), sample: `签约 ${m.leadsSigned} 条`,
      help: "上面那批已签约商机，从建档到合同生效的平均天数。合同还没生效的不计入。",
    },
    {
      key: "removal", label: "撤场回收率", value: pct(m.removalRecoveryRate), sample: `清点 ${m.removalCounted} / 应在柜 ${m.removalExpected}`,
      bad: m.removalRecoveryRate !== null && m.removalRecoveryRate < 1,
      help: "窗口内完工的撤机单，现场清点回收的充电宝数 ÷ 系统应在柜数（清点不符登记的差异计入应在柜数）。不到 100% 就是有宝没收回来，标 ▼。",
    },
  ];
}

export function OpsFlowMetricsPanel() {
  const allow = useCan();
  const can = allow("dashboard:overview:read");
  const [win, setWin] = React.useState("30");
  const range = React.useMemo(() => {
    const to = new Date(Date.now() + 86400_000);          // [from, to)：to 取明天，今天算在内
    const from = new Date(to.getTime() - Number(win) * 86400_000);
    return { from: ymd(from), to: ymd(to) };
  }, [win]);
  const q = useQuery({
    queryKey: ["opsFlowMetrics", range.from, range.to],
    queryFn: () => api.getOpsFlowMetrics(range),
    enabled: can,
  });
  if (!can) return null;

  return (
    <Card className="mt-6">
      <CardHeader className="flex flex-row flex-wrap items-center gap-2">
        <CardTitle>运营指标</CardTitle>
        <HelpNote>
          <p>运营核心流程的九个结果指标：工单、合同、装机、试借还、拓展、撤场。</p>
          <p className="mt-1">每个比率下面写着样本量；「无数据」表示窗口内没有可算的样本，不是 0%。</p>
        </HelpNote>
        <FilterSelect className="ms-auto" value={win} onChange={setWin} options={WINDOWS} aria-label="统计窗口" />
      </CardHeader>
      <CardContent>
        {q.isLoading && (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-24" />)}
          </div>
        )}
        {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {q.data && (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
            {tiles(q.data).map((t) => (
              <div key={t.key} data-surface="stat" className="rounded-card bg-secondary/60 p-4">
                <div className="flex items-center gap-1 txt-body text-muted-foreground">
                  <span className="truncate">{t.label}</span>
                  <HelpNote title={undefined}>{t.help}</HelpNote>
                </div>
                <div className={cn("mt-1.5 txt-display tabular-nums", t.value === null && "text-muted-foreground", t.bad && "text-warning-ink")}>
                  {t.value ?? "无数据"}{t.bad ? " ▼" : ""}
                </div>
                <div className="mt-0.5 txt-caption text-muted-foreground tabular-nums">{t.sample}</div>
              </div>
            ))}
          </div>
        )}
        {q.data && (
          <p className="mt-3 txt-caption text-muted-foreground tabular-nums">统计窗口 {q.data.from} ～ {q.data.to}（不含末日）</p>
        )}
      </CardContent>
    </Card>
  );
}
