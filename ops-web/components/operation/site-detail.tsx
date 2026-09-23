"use client";

// 站点详情抽屉（清单 OM-S2 详情部分）：8 个页签。
// 页签写进 URL（?no=&tab=），刷新与分享不丢；各页签的数据按需加载，打开抽屉不会一次拉八份。
//
// 「点位」页签就是点位的维护入口——点位不单独占菜单（清单 §1）。
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus } from "lucide-react";
import { RECENT_LIMIT, UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import type { Site, SitePoint, Cabinet, Contract, PricingDiff, ShareRule, AuditEntry } from "@/lib/types";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import { formatMarketTime } from "@/lib/market-time";
import { Drawer } from "@/components/ui/drawer";
import { Tabs } from "@/components/ui/tabs";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { EmptyState, Skeleton } from "@/components/ui/misc";
import { SummaryCard } from "@/components/ui/summary-card";
import { SiteStatsPanel } from "@/components/operation/site-stats";

const TABS = [
  { key: "basic", label: "基本信息" },
  { key: "points", label: "点位" },
  { key: "cabinets", label: "机柜" },
  { key: "contracts", label: "合同" },
  { key: "pricing", label: "计费" },
  { key: "sharing", label: "分成" },
  { key: "stats", label: "统计" },
  { key: "audit", label: "操作记录" },
];

const POINT_STATUS: StatusMap<SitePoint["status"]> = {
  ACTIVE: { label: "启用", tone: "success" },
  PAUSED: { label: "停用", tone: "muted" },
};
const ONLINE: StatusMap<"ONLINE" | "OFFLINE"> = {
  ONLINE: { label: "在线", tone: "success" },
  OFFLINE: { label: "离线", tone: "danger" },
};
const POINT_FIELDS: FieldDef[] = [
  { key: "name", label: "点位名称", required: true, maxLength: 128, placeholder: "L1 东门" },
  { key: "spotDesc", label: "位置描述", maxLength: 256, placeholder: "B1 层扶梯口左侧，靠近收银台" },
];

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 py-1.5">
      <div className="w-24 shrink-0 txt-caption text-muted-foreground">{label}</div>
      <div className="min-w-0 flex-1 txt-body">{children}</div>
    </div>
  );
}

export function SiteDetailDrawer({
  siteNo, tab, onTab, onClose,
}: { siteNo: string | null; tab: string | null; onTab: (t: string) => void; onClose: () => void }) {
  const qc = useQueryClient();
  const allow = useCan();
  const canPoint = allow("location:poi:update");
  const active = TABS.some((t) => t.key === tab) ? tab! : "basic";
  const [pointForm, setPointForm] = useState<Partial<SitePoint> | null>(null);
  const [editingPoint, setEditingPoint] = useState<SitePoint | undefined>();

  // 站点本体：列表页已有数据，但深链直接打开时列表可能还没加载，故独立取一次
  const siteQ = useQuery({
    queryKey: ["op", "site", siteNo],
    queryFn: async () => (await api.listSites({ page: 1, size: UNPAGED_SIZE })).list.find((s) => s.siteNo === siteNo),
    enabled: !!siteNo,
  });
  const site = siteQ.data as Site | undefined;

  const pointsQ = useQuery({
    queryKey: ["op", "site-points", siteNo],
    queryFn: () => api.listLocations({ page: 1, size: UNPAGED_SIZE, siteNo: siteNo! }),
    enabled: !!siteNo && ["points", "cabinets", "stats"].includes(active),
  });
  const cabinetsQ = useQuery({
    queryKey: ["op", "site-cabinets", siteNo],
    queryFn: () => api.listCabinets({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!siteNo && active === "cabinets",
  });
  const contractsQ = useQuery({
    queryKey: ["op", "site-contracts", siteNo],
    queryFn: () => api.listContracts({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!siteNo && ["contracts", "sharing"].includes(active),
  });
  const pricingQ = useQuery({
    queryKey: ["op", "site-pricing", siteNo],
    queryFn: async () => ({
      diffs: (await api.listPricingDiffs({ page: 1, size: UNPAGED_SIZE })).list,
      plans: (await api.listPricePlans({ page: 1, size: UNPAGED_SIZE })).list,
    }),
    enabled: !!siteNo && active === "pricing",
  });
  const sharingQ = useQuery({
    queryKey: ["op", "site-sharing", siteNo],
    queryFn: () => api.listShareRules({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!siteNo && active === "sharing",
  });
  const auditQ = useQuery({
    queryKey: ["op", "site-audit", siteNo],
    queryFn: () => api.listAudits({ page: 1, size: RECENT_LIMIT, keyword: siteNo! }),
    enabled: !!siteNo && active === "audit",
  });

  const savePoint = useMutation({
    mutationFn: (v: Partial<SitePoint>) => api.savePoint(v as Partial<SitePoint> & { locationNo?: string }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["op", "site-points", siteNo] });
      qc.invalidateQueries({ queryKey: ["op", "sites"] });
      notify.success("已保存"); setPointForm(null);
    },
  });

  const points = (pointsQ.data?.list ?? []).filter((p) => p.siteNo === siteNo);
  const siteCabinets = (cabinetsQ.data?.list ?? []).filter(
    (c) => c.siteNo === siteNo || points.some((p) => p.locationNo === c.locationNo),
  );
  const siteContracts = (contractsQ.data?.list ?? []).filter((c) => c.siteName === site?.name);

  const pointCols: Column<SitePoint>[] = [
    { header: "点位", cell: (p) => (
      <div className="min-w-0">
        <div className="truncate">{p.name}</div>
        <div className="truncate txt-caption text-muted-foreground">{p.locationNo}{p.spotDesc ? ` · ${p.spotDesc}` : ""}</div>
      </div>
    ) },
    { header: "机柜", className: "whitespace-nowrap text-right", cell: (p) => p.cabinetCount },
    { header: "状态", className: "whitespace-nowrap", cell: (p) => <StatusBadge map={POINT_STATUS} value={p.status} /> },
    ...(canPoint ? [{
      header: "操作",
      className: "whitespace-nowrap",
      cell: (p: SitePoint) => (
        <Button size="sm" variant="outline" onClick={() => { setEditingPoint(p); setPointForm({ ...p }); }}>
          <Pencil className="size-4" /> 编辑
        </Button>
      ),
    }] : []),
  ];

  const cabinetCols: Column<Cabinet>[] = [
    { header: "机柜", cell: (c) => (
      <div className="min-w-0">
        <div className="truncate font-mono">{c.cabinetNo}</div>
        <div className="truncate txt-caption text-muted-foreground">{c.locationName ?? "未归属点位"}</div>
      </div>
    ) },
    { header: "在线", className: "whitespace-nowrap", cell: (c) => <StatusBadge map={ONLINE} value={c.onlineStatus === "ONLINE" ? "ONLINE" : "OFFLINE"} /> },
    { header: "可用 / 总仓位", className: "whitespace-nowrap text-right", cell: (c) => `${c.availableCount ?? 0} / ${c.slotTotal}` },
    { header: "最后心跳", className: "whitespace-nowrap", cell: (c) => <span className="txt-caption">{c.lastHeartbeatAt ? formatMarketTime(c.lastHeartbeatAt) : "-"}</span> },
  ];

  const contractCols: Column<Contract>[] = [
    { header: "合同", cell: (c) => <span className="font-mono">{c.contractNo}</span> },
    { header: "场地方", cell: (c) => c.venueName },
    { header: "分成比例", className: "text-right", cell: (c) => `${(c.shareRate * 100).toFixed(1)}%` },
    { header: "进场费", className: "text-right", cell: (c) => money(c.entryFee) },
    { header: "有效期", className: "whitespace-nowrap", cell: (c) => {
      const days = Math.ceil((new Date(c.endAt).getTime() - Date.now()) / 86400_000);
      const warn = days < 0 ? "已过期" : days <= 30 ? `${days} 天后到期` : "";
      return (
        <span className="txt-caption">
          {c.startAt.slice(0, 10)} ～ {c.endAt.slice(0, 10)}
          {warn && <span className="ms-1 text-warning-ink">{warn}</span>}
        </span>
      );
    } },
  ];

  return (
    <Drawer
      open={!!siteNo}
      onOpenChange={(o) => !o && onClose()}
      title={site ? site.name : "站点详情"}
      desc={site ? `${site.siteNo} · ${site.venueName} · ${site.regionName}` : undefined}
      width="w-[720px]"
    >
      <Tabs tabs={TABS} value={active} onChange={onTab} />
      {siteQ.isLoading && <Skeleton className="h-40" />}
      {!siteQ.isLoading && !site && (
        <EmptyState title="站点不存在" desc="这个站点可能已被归档或删除，请回到列表重新选择。" />
      )}

      {site && active === "basic" && (
        <div>
          <Field label="站点名称">{site.name}{site.nameAr ? ` / ${site.nameAr}` : ""}</Field>
          <Field label="场地方">{site.venueName}</Field>
          <Field label="区域 · 场景">{site.regionName} · {site.sceneType}</Field>
          <Field label="地址">{site.address}</Field>
          <Field label="营业时间">{site.openHours || <span className="text-muted-foreground">未设置（视为 24 小时营业）</span>}</Field>
          <Field label="经纬度">
            {site.lat && site.lng
              ? `${site.lat.toFixed(5)}, ${site.lng.toFixed(5)}`
              : <span className="text-muted-foreground">未填写，地图上不会显示这个站点</span>}
          </Field>
          <Field label="归属代理">
            {site.agentNo
              ? <>{site.agentNo}<span className="ms-2 txt-caption text-muted-foreground">改归属请走「代理商管理 › 资产划拨」，那里会连同点位与机柜一起变更并留流水</span></>
              : <>平台直营<span className="ms-2 txt-caption text-muted-foreground">如需划给代理，请走「代理商管理 › 资产划拨」</span></>}
          </Field>
          <Field label="状态"><StatusBadge map={{ ACTIVE: { label: "营业中", tone: "success" }, PAUSED: { label: "暂停营业", tone: "warning" } }} value={site.status} /></Field>
        </div>
      )}

      {site && active === "points" && (
        <div>
          {canPoint && (
            <div className="mb-3 flex justify-end">
              <Button size="sm" onClick={() => { setEditingPoint(undefined); setPointForm({ siteNo: site.siteNo, siteName: site.name, status: "ACTIVE" }); }}>
                <Plus className="size-4" /> 新增点位
              </Button>
            </div>
          )}
          <DataTable
            rowKey={(p: SitePoint) => p.locationNo}
            columns={pointCols}
            rows={pointsQ.isLoading ? undefined : points}
            loading={pointsQ.isLoading}
            empty="这个站点还没有点位。点位是机柜的投放位置，先建点位才能上架机柜。"
          />
        </div>
      )}

      {site && active === "cabinets" && (
        <DataTable
          rowKey={(c: Cabinet) => c.cabinetNo}
          columns={cabinetCols}
          rows={cabinetsQ.isLoading || pointsQ.isLoading ? undefined : siteCabinets}
          loading={cabinetsQ.isLoading || pointsQ.isLoading}
          empty="这个站点还没有机柜。机柜在「设备管理」建档并绑定到本站点的点位后会出现在这里。"
        />
      )}

      {site && active === "contracts" && (
        <DataTable
          rowKey={(c: Contract) => c.contractNo}
          columns={contractCols}
          rows={contractsQ.isLoading ? undefined : siteContracts}
          loading={contractsQ.isLoading}
          empty="这个站点没有进场合同。合同决定给场地方的分成比例，没有合同意味着分成无依据。"
        />
      )}

      {site && active === "pricing" && (
        <div>
          {pricingQ.isLoading && <Skeleton className="h-24" />}
          {pricingQ.data && (() => {
            // 取价优先级：站点专属的差异化定价 > 全平台默认方案。
            // 差异化定价自带计费参数（不挂方案号），故两种来源分别渲染。
            const diff = pricingQ.data.diffs.find((d: PricingDiff) => d.dimension === "SITE" && d.siteNo === site.siteNo);
            const fallback = pricingQ.data.plans.find((p) => p.scope === "默认" || p.scope === "DEFAULT") ?? pricingQ.data.plans[0];
            return (
              <div>
                <SummaryCard
                  label="当前生效的计费"
                  value={diff ? `站点专属 · ${diff.ruleNo}` : fallback ? fallback.name : "—"}
                  sub={diff
                    ? "命中原因：为本站点单独配置了差异化定价"
                    : fallback ? "命中原因：没有站点专属配置，落到默认方案" : "没有任何可用方案，将按系统兜底价计费"}
                />
                {diff ? (
                  <div className="mt-3">
                    <Field label="免费时长">{diff.freeMinutes} 分钟</Field>
                    <Field label="单价">{money(diff.unitPrice)}</Field>
                    <Field label="日封顶">{money(diff.capDaily)}</Field>
                  </div>
                ) : fallback ? (
                  <div className="mt-3">
                    <Field label="免费时长">{fallback.freeMinutes} 分钟</Field>
                    <Field label="计费">每 {fallback.unitMinutes} 分钟 {money(fallback.unitPrice, fallback.currency)}</Field>
                    <Field label="日封顶">{money(fallback.capDaily, fallback.currency)}</Field>
                    <Field label="买断价">{money(fallback.buyoutPrice, fallback.currency)}</Field>
                  </div>
                ) : null}
              </div>
            );
          })()}
        </div>
      )}

      {site && active === "sharing" && (
        <div>
          {sharingQ.isLoading || contractsQ.isLoading ? <Skeleton className="h-24" /> : (() => {
            // 分成现在按「场地方」配，站点通过合同关联到场地方（清单 D2 未定前的现状）
            const venueRules = (sharingQ.data?.list ?? []).filter((r: ShareRule) => r.payeeName === site.venueName);
            const total = venueRules.reduce((s, r) => s + r.rate, 0);
            if (!venueRules.length) {
              return <EmptyState title="没有配置分成" desc={`场地方「${site.venueName}」还没有分成规则，这个站点的收入目前全部留在平台。到「财务管理 › 分润规则」新增。`} />;
            }
            return (
              <div>
                {venueRules.map((r) => (
                  <Field key={r.ruleNo} label={r.payeeName}>
                    {(r.rate * 100).toFixed(1)}%
                    <span className="ms-2 txt-caption text-muted-foreground">{r.dimension === "VENUE" ? "场地方" : "代理商"} · {r.ruleNo}</span>
                  </Field>
                ))}
                <Field label="平台留存">{((1 - total) * 100).toFixed(1)}%</Field>
                <p className="mt-2 txt-caption text-muted-foreground">
                  说明：分成规则现在按分成方配置、不含站点维度，这里按「站点 → 场地方」关联展示。
                  「站点分成」页会在口径定案后提供按站点直接配置的入口。
                </p>
              </div>
            );
          })()}
        </div>
      )}

      {site && active === "stats" && <SiteStatsPanel siteNo={site.siteNo} />}

      {site && active === "audit" && (
        <DataTable
          rowKey={(a: AuditEntry) => a.id}
          columns={[
            { header: "时间", className: "whitespace-nowrap", cell: (a: AuditEntry) => <span className="txt-caption">{fmtTime(a.createdAt)}</span> },
            { header: "操作人", className: "whitespace-nowrap", cell: (a: AuditEntry) => a.actor },
            { header: "动作", cell: (a: AuditEntry) => a.action },
            { header: "对象", cell: (a: AuditEntry) => <span className="txt-caption text-muted-foreground">{a.target}</span> },
          ]}
          rows={auditQ.isLoading ? undefined : auditQ.data?.list}
          loading={auditQ.isLoading}
          empty="没有与这个站点相关的操作记录。新增、编辑、暂停营业等动作都会记在这里。"
        />
      )}

      <FormDrawer
        open={!!pointForm}
        onOpenChange={(o) => !o && setPointForm(null)}
        titleNew="新增点位"
        titleEdit={`编辑点位 ${editingPoint?.locationNo ?? ""}`}
        isEdit={!!editingPoint}
        fields={POINT_FIELDS}
        value={(pointForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPointForm(v as Partial<SitePoint>)}
        onSubmit={() => pointForm && savePoint.mutate(pointForm)}
        submitting={savePoint.isPending}
      />
    </Drawer>
  );
}
