"use client";

// 设备归属卡（开发计划 F0-2；分析：docs/technical/设备绑定与商家归属-现状分析.md §四.1）。
//
// 「谁看得到」与「谁分得到」今天读的是两份数据：
//   · 商家看得到这台设备与它的订单 —— 读机柜上的 agentNo（由点位派生，订单下单时快照它）；
//   · 分成给谁 —— 读站点上的责任行（loc_site_agent），没配责任行才回落到订单快照的 agentNo。
// 两处由两个界面维护（商家管理的划拨 / 站点详情 › 分成），可以不一致而且不报错。
// 这张卡只读不改：把两者并排摆出来，不一致时标红并给去处 —— 后端统一口径前先让运营看得见。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { RefLink } from "@/components/ref-link";
import { useCan } from "@/lib/hooks/use-can";
import { fmtTime } from "@/lib/utils";
import type { Cabinet } from "@/lib/types";
import { ownershipIssues, effectiveRoles as effective } from "@/lib/ownership";

const ROLE: Record<string, string> = { INVEST: "出资", DEVELOP: "拓展", OPERATE: "运维", REFER: "牵线" };

export function OwnershipCard({ cabinet }: { cabinet: Cabinet }) {
  const allow = useCan();
  const canSeeRoles = allow("location:poi:read");
  const rolesQ = useQuery({
    queryKey: ["site-agents", cabinet.siteNo],
    queryFn: () => api.listSiteAgents(cabinet.siteNo!),
    enabled: !!cabinet.siteNo && canSeeRoles,
  });
  const roles = rolesQ.data ?? [];
  const issues = rolesQ.data ? ownershipIssues(cabinet, roles) : [];
  const live = effective(roles);

  return (
    <Card>
      <CardHeader><CardTitle>归属</CardTitle></CardHeader>
      <CardContent className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Field label="商家（看得到）" className="mb-0">
          {cabinet.agentNo ? <RefLink kind="agent" no={cabinet.agentNo} /> : <span className="text-muted-foreground">平台直营</span>}
        </Field>
        <Field label="站点 › 点位" className="mb-0">
          {cabinet.siteNo
            ? <span><RefLink kind="site" no={cabinet.siteNo} /> › <span className="tabular-nums">{cabinet.locationNo}</span></span>
            : <span className="text-muted-foreground">未绑定</span>}
        </Field>
        <Field label="出库池" className="mb-0">
          {cabinet.issuedAgentNo
            ? <span><RefLink kind="agent" no={cabinet.issuedAgentNo} />{cabinet.issuedAt && <span className="txt-caption text-muted-foreground"> · {fmtTime(cabinet.issuedAt)} 出库</span>}</span>
            : <span className="text-muted-foreground">{cabinet.warehouseNo ? `在仓 ${cabinet.warehouseNo}` : "—"}</span>}
        </Field>
        <Field label="分成给（分得到）" className="mb-0">
          {!cabinet.siteNo ? <span className="text-muted-foreground">未绑定，不产生分成</span>
            : !canSeeRoles ? <span className="text-muted-foreground">无站点读取权限</span>
              : rolesQ.isLoading ? <span className="text-muted-foreground">加载中…</span>
                : live.length
                  ? <span className="flex flex-wrap gap-1">{live.map((r) => (
                    <Badge key={`${r.agentNo}-${r.role}`} tone="outline">{ROLE[r.role] ?? r.role} · {r.agentName ?? r.agentNo}</Badge>
                  ))}</span>
                  : <span className="text-muted-foreground">站点未配责任行，分成回落到机柜的商家</span>}
        </Field>
        {issues.length > 0 && (
          <div className="col-span-2 rounded-card bg-destructive-tint px-3.5 py-2 txt-body text-destructive-ink md:col-span-4" role="alert">
            {issues.map((t) => <div key={t}>{t}</div>)}
            <div className="txt-caption">
              去 <RefLink kind="site" no={cabinet.siteNo!} label="站点详情 › 分成" /> 核对责任行；看得到与分得到不一致时，商家会看到订单却拿不到分成，或反过来。
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
