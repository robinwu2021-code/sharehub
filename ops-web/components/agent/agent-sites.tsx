"use client";

// 商家详情 › 站点与点位、设备（设备出库与绑定 · 开发计划 F3）。
// 用户裁决 2026-10-28：站点就是门店，商家可以有多个站点、多个点位；一期由运营代建；绑定在运营端做。
// 建站带 agentNo：后端同事务建站 + 默认点位 + 归属 + OPERATE 责任行（4a508d8b · V162），
// 场地方 / 区域 / 营业时间可空，地址与坐标必填。
import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { featureReady } from "@/lib/backend-ready";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { SummaryCard } from "@/components/ui/summary-card";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import { RefLink } from "@/components/ref-link";
import { POOL_STATE_MAP, poolStateOf } from "@/components/inventory/issued-pool";
import type { Agent, Cabinet, Site, SitePoint } from "@/lib/types";

/** @form POST /api/ops/sites */
const SITE_FIELDS: FieldDef[] = [
  { key: "name", label: "站点名称", required: true, maxLength: 128, placeholder: "如：Marina Mall 店" },
  { key: "address", label: "地址", type: "address", latKey: "lat", lngKey: "lng", required: true, maxLength: 256,
    placeholder: "输入地点名或地址，边打边找", help: "选中候选后坐标自动带上；C 端「找附近」按坐标排" },
];
/** @form POST /api/ops/locations */
const POINT_FIELDS: FieldDef[] = [
  { key: "name", label: "点位名称", required: true, maxLength: 128, placeholder: "如：一层服务台" },
  { key: "spotDesc", label: "位置说明", maxLength: 128, placeholder: "如：收银台旁" },
];

/** 站点与点位：默认站点置顶，每个站点下列点位（默认点位标出）；运营可新建站点、新建点位。 */
export function AgentSites({ agent }: { agent: Agent }) {
  const qc = useQueryClient();
  const allow = useCan();
  const canCreate = allow("location:poi:create") && featureReady("sites.merchant");
  const [siteForm, setSiteForm] = useState<Partial<Site> | null>(null);
  const [pointForm, setPointForm] = useState<Partial<SitePoint> | null>(null);
  const sitesQ = useQuery({ queryKey: ["sites", "of-agent", agent.agentNo], queryFn: () => api.listSites({ agentNo: agent.agentNo, size: UNPAGED_SIZE }) });
  const pointsQ = useQuery({ queryKey: ["locations", "of-agent", agent.agentNo], queryFn: () => api.listLocations({ agentNo: agent.agentNo, size: UNPAGED_SIZE }) });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["sites"] });
    qc.invalidateQueries({ queryKey: ["locations"] });
    qc.invalidateQueries({ queryKey: ["op", "sites"] });
  };
  const saveSite = useMutation({
    mutationFn: (v: Partial<Site>) => api.saveSite({ ...v, agentNo: agent.agentNo, lat: Number(v.lat), lng: Number(v.lng) }),
    onSuccess: (s) => { notify.success(`已建站点 ${s.name}，默认点位一并建好`); setSiteForm(null); refresh(); },
  });
  const savePoint = useMutation({
    mutationFn: (v: Partial<SitePoint>) => api.savePoint(v),
    onSuccess: (l) => { notify.success(`已建点位 ${l.name}`); setPointForm(null); refresh(); },
  });

  const sites = [...(sitesQ.data?.list ?? [])].sort((a, b) =>
    Number(b.siteNo === agent.defaultSiteNo) - Number(a.siteNo === agent.defaultSiteNo));
  const points = pointsQ.data?.list ?? [];

  if (sitesQ.isLoading) return <div className="txt-caption text-muted-foreground">加载中…</div>;
  return (
    <div className="space-y-4">
      <Notice className="mb-0">
        站点是商家经营设备的场所（商家端叫「我的站点」），一期由运营代建。每个站点建好就有一个默认点位；从出库管理绑定设备时，不选点位就落在默认站点的默认点位。
        {!agent.defaultSiteNo && " 这个商家还没补齐位置与行业，所以还没有默认站点 —— 补齐后系统自动建。"}
      </Notice>
      {canCreate && (
        <div className="flex justify-end">
          <Button size="sm" onClick={() => setSiteForm({})} disabled={!agent.defaultSiteNo}
            title={agent.defaultSiteNo ? undefined : "先补齐商家位置与行业"}>新建站点</Button>
        </div>
      )}
      {!sites.length ? (
        <p className="txt-body text-muted-foreground">还没有站点。</p>
      ) : sites.map((s) => {
        const mine = points.filter((p) => p.siteNo === s.siteNo);
        return (
          <section key={s.siteNo} className="rounded-card border border-border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <RefLink kind="site" no={s.siteNo} label={s.name} />
              {s.siteNo === agent.defaultSiteNo && <Badge tone="outline">默认站点</Badge>}
              <span className="txt-caption text-muted-foreground">{s.address}</span>
              {canCreate && <Button size="sm" variant="outline" className="ms-auto" onClick={() => setPointForm({ siteNo: s.siteNo })}>新建点位</Button>}
            </div>
            <ul className="mt-2 space-y-1">
              {mine.length ? mine.map((p) => (
                <li key={p.locationNo} className="flex items-center gap-2 txt-body">
                  <span className="tabular-nums text-muted-foreground">{p.locationNo}</span>
                  <span>{p.name}</span>
                  {p.isDefault && <Badge tone="outline">默认点位</Badge>}
                  {p.status !== "ACTIVE" && <Badge tone="warning">已暂停</Badge>}
                </li>
              )) : <li className="txt-caption text-muted-foreground">没有点位</li>}
            </ul>
          </section>
        );
      })}

      <FormDrawer open={!!siteForm} onOpenChange={(o) => !o && setSiteForm(null)}
        titleNew={`为 ${agent.name} 新建站点`} titleEdit="" isEdit={false} fields={SITE_FIELDS}
        value={(siteForm ?? {}) as Record<string, unknown>} onChange={(v) => setSiteForm(v as Partial<Site>)}
        onSubmit={() => siteForm && saveSite.mutate(siteForm)} submitting={saveSite.isPending} />
      <FormDrawer open={!!pointForm} onOpenChange={(o) => !o && setPointForm(null)}
        titleNew="新建点位" titleEdit="" isEdit={false} fields={POINT_FIELDS}
        value={(pointForm ?? {}) as Record<string, unknown>} onChange={(v) => setPointForm({ ...pointForm, ...(v as Partial<SitePoint>) })}
        onSubmit={() => pointForm && savePoint.mutate(pointForm)} submitting={savePoint.isPending} />
    </div>
  );
}

/** 设备：出库管理汇总 + 该商家出库的全部设备（未绑定 / 已绑定 / 已上线）。 */
export function AgentDevices({ agentNo }: { agentNo: string }) {
  const ready = featureReady("devices.pool");
  const sumQ = useQuery({ queryKey: ["issued-summary", agentNo], queryFn: () => api.issuedSummary(agentNo), enabled: ready });
  const listQ = useQuery({
    queryKey: ["issued-pool", agentNo, "ALL"],
    queryFn: () => api.listCabinets({ issuedAgentNo: agentNo, pool: "ALL", size: UNPAGED_SIZE }),
    enabled: ready,
  });
  if (!ready) return <Notice>出库管理的后端接口尚未上线。</Notice>;
  const s = sumQ.data?.[0];
  const cols: Column<Cabinet>[] = [
    { header: "柜机号", cell: (c) => <Link href={`/devices/detail?no=${c.cabinetNo}`} className="txt-strong tabular-nums hover:underline">{c.cabinetNo}</Link> },
    { header: "状态", cell: (c) => <StatusBadge map={POOL_STATE_MAP} value={poolStateOf(c)} /> },
    { header: "站点 › 点位", cell: (c) => c.siteNo
      ? <span><RefLink kind="site" no={c.siteNo} label={c.locationName ?? c.siteNo} /> <span className="text-muted-foreground tabular-nums">› {c.locationNo}</span></span>
      : <span className="text-muted-foreground">未绑定</span> },
    { header: "出库时间", cell: (c) => <span className="text-muted-foreground">{c.issuedAt ? fmtTime(c.issuedAt) : "-"}</span> },
  ];
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <SummaryCard label="待绑定" value={String(s?.unbound ?? 0)} sub="已出库，还没定点位" />
        <SummaryCard label="待安装" value={String(s?.bound ?? 0)} sub="已绑定点位，未上线" />
        <SummaryCard label="已上线" value={String(s?.live ?? 0)} sub="产生营收" />
      </div>
      <div className="flex justify-end">
        <Link href="/inventory?tab=issued" className="txt-caption text-primary hover:underline">出库管理 →</Link>
      </div>
      <DataTable rowKey={(c: Cabinet) => c.cabinetNo} columns={cols} rows={listQ.data?.list}
        loading={listQ.isLoading} error={listQ.error} onRetry={listQ.refetch}
        empty="该商家暂无已出库设备，请前往库存管理办理出库。" />
    </div>
  );
}
