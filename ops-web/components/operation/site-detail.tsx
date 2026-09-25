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
import type {
  Site, SitePoint, Cabinet, Contract, PlanScope, ShareRule, AuditEntry, SiteAgent, SiteAgentRole,
} from "@/lib/types";
import { SITE_AGENT_ROLES } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { money, fmtTime } from "@/lib/utils";
import { formatMarketTime } from "@/lib/rules/market-time";
import { Drawer } from "@/components/ui/drawer";
import { Tabs } from "@/components/ui/tabs";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Notice } from "@/components/ui/notice";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Button } from "@/components/ui/button";
import { EmptyState, Skeleton } from "@/components/ui/misc";
import { SummaryCard } from "@/components/ui/summary-card";
import { SiteStatsPanel } from "@/components/operation/site-stats";

/**
 * 站点五态（2026-09-25 起，与「门店生命周期」合并后的唯一一套）。
 *
 * <p>色调按**要不要人管**分：筹备中与撤场中是在途、需要推进（default/warning），
 * 暂停营业是异常但可自恢复，已关闭是终态（muted，不再吸引注意力）。
 */
export const SITE_STATUS: StatusMap<Site["status"]> = {
  PREPARING: { label: "筹备中", tone: "default" },
  ACTIVE: { label: "营业中", tone: "success" },
  PAUSED: { label: "暂停营业", tone: "warning" },
  WITHDRAWING: { label: "撤场中", tone: "warning" },
  CLOSED: { label: "已关闭", tone: "muted" },
};


const TABS = [
  { key: "basic", label: "基本信息" },
  { key: "points", label: "点位" },
  { key: "cabinets", label: "机柜" },
  { key: "contracts", label: "合同" },
  { key: "partners", label: "合作伙伴" },
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
  const [partnerForm, setPartnerForm] = useState<Partial<SiteAgent> | null>(null);

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
  /*
   * 站点上的伙伴责任（ADR-027 §三）。一个站点上有四件事各有其人：
   * 谁出的钱、谁找来的、谁在维护、谁牵的线 —— 压成一个比例就不可追溯，
   * 结算争议时说不清「这 8% 里几个点是运维、几个点是出资」。
   */
  const partnersQ = useQuery({
    queryKey: ["op", "site-agents", siteNo],
    queryFn: () => api.listSiteAgents(siteNo!),
    enabled: !!siteNo && active === "partners",
  });
  const agentOptsQ = useQuery({
    queryKey: ["op", "agent-options-for-site"],
    queryFn: () => api.listAgents({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!siteNo && active === "partners",
  });
  const savePartner = useMutation({
    mutationFn: (v: Partial<SiteAgent>) => api.saveSiteAgent(siteNo!, v),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["op", "site-agents", siteNo] });
      notify.success("已保存");
      setPartnerForm(null);
    },
  });
  const removePartner = useMutation({
    mutationFn: (id: number) => api.removeSiteAgent(siteNo!, id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["op", "site-agents", siteNo] }); notify.success("已撤销"); },
  });

  const pricingQ = useQuery({
    queryKey: ["op", "site-pricing", siteNo],
    queryFn: async () => {
      const plans = (await api.listPricePlans({ page: 1, size: UNPAGED_SIZE })).list;
      // 适用范围挂在方案上，没有「按站点查范围」的接口 —— 方案是配置量级（几十条），
      // 逐个取比新开一个反查接口便宜，也不必让后端多一个只有这里用的查询。
      const scopes = (await Promise.all(plans.map((p) => api.listPlanScopes(p.planNo)))).flat();
      return { plans, scopes };
    },
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
      // 逐段过滤空值再拼：缺一段时不要在标题里留下「· undefined」——
      // 那既没告诉用户缺了什么，又让人怀疑整页数据都不可信
      desc={site ? [site.siteNo, site.venueName, site.regionName || site.regionId].filter(Boolean).join(" · ") : undefined}
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
          <Field label="区域 · 场景">{[site.regionName || site.regionId, site.sceneType].filter(Boolean).join(" · ") || "未设置"}</Field>
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
          <Field label="状态"><StatusBadge map={SITE_STATUS} value={site.status} /></Field>
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

      {site && active === "partners" && (() => {
        const ROLE: StatusMap<SiteAgentRole> = {
          INVEST: { label: "出资", tone: "outline" },
          DEVELOP: { label: "拓展", tone: "default" },
          OPERATE: { label: "运维", tone: "success" },
          REFER: { label: "牵线", tone: "muted" },
        };
        const WHY: Record<SiteAgentRole, string> = {
          INVEST: "买设备的人，分资产收益",
          DEVELOP: "找场地、谈判、签合同的人",
          OPERATE: "装机补货维修接工单，并负责经营优化",
          REFER: "只介绍关系不谈判；介绍费签约时一次性付，不进逐单分润",
        };
        const agentOpts = (agentOptsQ.data?.list ?? [])
          .filter((a) => !a.archivedAt)
          .map((a) => ({ value: a.agentNo, label: `${a.name}（${a.agentNo}）` }));
        const fields: FieldDef[] = [
          { key: "agentNo", label: "合作伙伴", type: "select", required: true, section: "责任",
            options: [{ value: "", label: "请选择合作伙伴" }, ...agentOpts] },
          { key: "role", label: "承担的责任", type: "select", required: true, section: "责任",
            options: SITE_AGENT_ROLES.map((r) => ({ value: r, label: `${ROLE[r].label} —— ${WHY[r]}` })),
            help: "同一个人在同一个站点，「牵线」与「拓展」只能算其一——牵线是拓展的弱形式" },
          { key: "remark", label: "依据", maxLength: 256, section: "责任",
            placeholder: "如：全程谈下进场合同",
            help: "结算争议时用它回答「凭什么是这个责任」，一句人话即可" },
          // 牵线费只在「牵线」这一档出现：另外三档是按 GMV 的持续分成，走分润规则的比例。
          // 给它们也显示一个金额框，等于邀请运营去填一个**没有任何代码会读**的数。
          ...(partnerForm?.role === "REFER"
            ? [{
                key: "oneOffAmount", label: "牵线费（一次性）", type: "number" as const, min: 0, section: "责任",
                help: "签合同时一次性付，不进逐单分润。留空 = 还没谈定（与 0「明确不付」不是一回事）",
              }]
            : []),
          { key: "effectiveFrom", label: "生效起", type: "date", section: "生效期", help: "留空 = 立即" },
          { key: "effectiveTo", label: "生效止", type: "date", section: "生效期", help: "留空 = 长期" },
        ];
        const cols: Column<SiteAgent>[] = [
          { header: "伙伴", cell: (r) => (
            <div className="min-w-0">
              <div className="truncate">{r.agentName ?? r.agentNo}</div>
              <div className="truncate txt-caption text-muted-foreground">
                {r.agentNo}{r.agentType === "CITY_PARTNER" ? " · 城市合伙人" : ""}
              </div>
            </div>
          ) },
          { header: "责任", className: "whitespace-nowrap", cell: (r) => <StatusBadge map={ROLE} value={r.role} /> },
          { header: "依据", cell: (r) => <span className="txt-caption text-muted-foreground">{r.remark || "—"}</span> },
          // 只有牵线有一次性对价；别的责任显示「—」而不是空白 —— 空白会被读成数据缺失
          { header: "牵线费", className: "whitespace-nowrap text-right", cell: (r) => (
            r.role !== "REFER" ? <span className="text-muted-foreground">—</span>
              : r.oneOffAmount == null
                ? <span className="text-muted-foreground">未定</span>
                : <span className="tabular-nums">{r.oneOffAmount.toFixed(2)}</span>
          ) },
          { header: "生效期", className: "whitespace-nowrap", cell: (r) => (
            <span className="txt-caption text-muted-foreground">
              {(r.effectiveFrom ?? "").slice(0, 10) || "立即"} ~ {(r.effectiveTo ?? "").slice(0, 10) || "长期"}
            </span>
          ) },
          { header: "操作", cell: (r) => (
            <div className="flex w-max gap-2">
              <Button size="sm" variant="outline" onClick={() => setPartnerForm({ ...r })}>编辑</Button>
              <Button size="sm" variant="outline" onClick={() => r.id != null && removePartner.mutate(r.id)}>撤销</Button>
            </div>
          ) },
        ];
        return (
          <div>
            <Notice>
              一个站点上「谁出的钱、谁找来的、谁在维护、谁牵的线」各有其位，各拿各的钱。
              <b>责任决定分钱，只有运营方能配。</b>
              目前配了也不改变分账 —— 按责任分条生成分润是下一批（A2-2）。
            </Notice>
            <div className="my-3">
              <Button size="sm" onClick={() => setPartnerForm({ role: "OPERATE" })}>
                <Plus className="size-4" /> 新增责任
              </Button>
            </div>
            <DataTable
              rowKey={(r: SiteAgent) => String(r.id)}
              columns={cols}
              rows={partnersQ.data}
              loading={partnersQ.isLoading}
              error={partnersQ.error}
              onRetry={partnersQ.refetch}
              empty="这个站点还没有配责任。没有责任行时，分账按站点归属的那个代理走老路（A2-2 之后仍有回落）。"
            />
            <FormDrawer
              open={!!partnerForm}
              onOpenChange={(o) => !o && setPartnerForm(null)}
              titleNew="新增责任"
              titleEdit="编辑责任"
              isEdit={partnerForm?.id != null}
              fields={fields}
              value={(partnerForm ?? {}) as Record<string, unknown>}
              onChange={(v) => setPartnerForm(v as Partial<SiteAgent>)}
              onSubmit={() => partnerForm && savePartner.mutate(partnerForm)}
              submitting={savePartner.isPending}
            />
          </div>
        );
      })()}

      {site && active === "pricing" && (
        <div>
          {pricingQ.isLoading && <Skeleton className="h-24" />}
          {pricingQ.data && (() => {
            /*
             * 取价优先级照 ADR-028 的层序：越具体越优先。这里只解释**站点这一层能看到的部分** ——
             * 真正成单时还会按机柜带上点位 / 厂商 / 型号再裁决一次，所以这张卡片说的是
             * 「站点级看下来命中谁」，不是「每一单一定按它」。
             */
            const { plans, scopes } = pricingQ.data;
            const byPlan = new Map(plans.map((p) => [p.planNo, p]));
            const LEVELS: PlanScope["scopeType"][] = ["SITE", "VENUE", "AGENT", "SCENE", "REGION", "ALL"];
            const refOf = (lv: PlanScope["scopeType"]) => ({
              SITE: site.siteNo, VENUE: site.venueNo ?? undefined, AGENT: site.agentNo ?? undefined,
              SCENE: site.sceneType, REGION: site.regionId, ALL: "*",
              DEVICE: undefined, LOCATION: undefined,
            })[lv];
            let hit: PlanScope | undefined;
            let hitLevel: PlanScope["scopeType"] | undefined;
            for (const lv of LEVELS) {
              const ref = refOf(lv);
              if (!ref) continue;   // 这一层无从判断 → 不参与，**不当成通配**
              const found = scopes.find((x) => x.scopeType === lv && x.scopeRef === ref
                && byPlan.get(x.planNo)?.status === "ACTIVE");
              if (found) { hit = found; hitLevel = lv; break; }
            }
            const plan = hit ? byPlan.get(hit.planNo) : undefined;
            const LEVEL_WHY: Record<string, string> = {
              SITE: "为本站点单独配了方案", VENUE: "按场地方统一价（通常来自进场合同）",
              AGENT: "按代理商统一价", SCENE: `按场景「${site.sceneType}」`,
              REGION: "按区域定价", ALL: "没有更具体的配置，落到默认方案",
            };
            return (
              <div>
                <SummaryCard
                  label="当前生效的计费"
                  value={plan ? plan.name : "—"}
                  sub={hitLevel
                    ? `命中原因：${LEVEL_WHY[hitLevel]}（${hit!.scopeType} · ${hit!.scopeRef}）`
                    : "没有任何适用范围命中——这种站点下单会被拒绝，请至少配一条默认方案"}
                />
                {plan && (
                  <div className="mt-3">
                    <Field label="方案号">{plan.planNo}</Field>
                    <Field label="免费时长">{plan.freeMinutes} 分钟</Field>
                    <Field label="计费">每 {plan.unitMinutes} 分钟 {money(plan.unitPrice, plan.currency)}</Field>
                    <Field label="日封顶">{money(plan.capDaily, plan.currency)}</Field>
                    <Field label="买断价">{money(plan.buyoutPrice, plan.currency)}</Field>
                  </div>
                )}
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
