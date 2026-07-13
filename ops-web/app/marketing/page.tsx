"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type {
  Coupon, Campaign, PushMessage, Referral, AdSlot, AdCampaign, AdDelivery, PageResult,
} from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "coupons", label: "优惠券", phase: 2 as const },
  { key: "campaigns", label: "活动", phase: 2 as const },
  { key: "push", label: "推送触达", phase: 3 as const },
  { key: "referral", label: "邀请裂变", phase: 3 as const },
  { key: "ad-slots", label: "广告位", phase: 3 as const },
  { key: "ad-campaigns", label: "广告活动", phase: 3 as const },
  { key: "ad-delivery", label: "投放与曝光", phase: 3 as const },
];
const COUPON_FIELDS: FieldDef[] = [
  { key: "name", label: "名称", placeholder: "新人立减" },
  { key: "type", label: "类型", type: "select", options: [{ value: "CUT", label: "立减" }, { value: "DISCOUNT", label: "折扣" }] },
  { key: "value", label: "面额 / 折扣", type: "number" },
  { key: "threshold", label: "门槛（满 X 元）", type: "number" },
  { key: "stock", label: "库存", type: "number" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "进行中" }, { value: "PAUSED", label: "暂停" }] },
];
const CAMPAIGN_FIELDS: FieldDef[] = [
  { key: "name", label: "活动名称", placeholder: "夏日充电狂欢" },
  { key: "kind", label: "类型", placeholder: "满减 / 拉新 / 签到" },
  { key: "rule", label: "规则", placeholder: "满 20 减 5" },
  { key: "status", label: "状态", type: "select", options: [{ value: "DRAFT", label: "草稿" }, { value: "RUNNING", label: "进行中" }, { value: "ENDED", label: "已结束" }] },
  { key: "startAt", label: "开始时间", placeholder: "2026-07-01 00:00:00" },
  { key: "endAt", label: "结束时间", placeholder: "2026-07-31 23:59:59" },
];
const PUSH_FIELDS: FieldDef[] = [
  { key: "title", label: "标题", placeholder: "您有一张新券待领取" },
  { key: "channel", label: "渠道", type: "select", options: [{ value: "APP_PUSH", label: "App 推送" }, { value: "SUBSCRIBE", label: "订阅消息" }] },
  { key: "audience", label: "受众", placeholder: "全部用户 / 沉默用户" },
  { key: "status", label: "状态", type: "select", options: [{ value: "DRAFT", label: "草稿" }, { value: "SENT", label: "已发送" }] },
];
const SLOT_FIELDS: FieldDef[] = [
  { key: "cabinetNo", label: "机柜号", placeholder: "CAB-0001" },
  { key: "position", label: "位置", type: "select", options: [{ value: "SCREEN", label: "屏幕" }, { value: "BODY", label: "机身" }] },
  { key: "size", label: "尺寸", placeholder: "1080x1920" },
  { key: "status", label: "状态", type: "select", options: [{ value: "IDLE", label: "空闲" }, { value: "OCCUPIED", label: "已占用" }] },
];
const AD_FIELDS: FieldDef[] = [
  { key: "advertiser", label: "广告主", placeholder: "某品牌" },
  { key: "creative", label: "创意", placeholder: "创意素材描述" },
  { key: "targeting", label: "定向", placeholder: "城市 / 人群 / 时段" },
  { key: "status", label: "状态", type: "select", options: [{ value: "DRAFT", label: "草稿" }, { value: "RUNNING", label: "投放中" }, { value: "ENDED", label: "已结束" }] },
  { key: "startAt", label: "开始时间", placeholder: "2026-07-01 00:00:00" },
  { key: "endAt", label: "结束时间", placeholder: "2026-07-31 23:59:59" },
];

function MarketingInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "coupons");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [couponForm, setCouponForm] = useState<Partial<Coupon> | null>(null);
  const [campaignForm, setCampaignForm] = useState<Partial<Campaign> | null>(null);
  const [pushForm, setPushForm] = useState<Partial<PushMessage> | null>(null);
  const [slotForm, setSlotForm] = useState<Partial<AdSlot> | null>(null);
  const [adForm, setAdForm] = useState<Partial<AdCampaign> | null>(null);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const canEditCoupon = allow("marketing:coupon:issue");
  const canEditCampaign = allow("marketing:campaign:manage");
  const canEditPush = allow("marketing:push:send");
  const canEditAd = allow("marketing:ad:manage");
  const saveCoupon = useMutation({
    mutationFn: (c: Partial<Coupon>) => api.saveCoupon(c),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setCouponForm(null); },
  });
  const saveCampaign = useMutation({
    mutationFn: (c: Partial<Campaign>) => api.saveCampaign(c),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setCampaignForm(null); },
  });
  const savePush = useMutation({
    mutationFn: (p: Partial<PushMessage>) => api.savePushMessage(p),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setPushForm(null); },
  });
  const saveSlot = useMutation({
    mutationFn: (s: Partial<AdSlot>) => api.saveAdSlot(s),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setSlotForm(null); },
  });
  const saveAd = useMutation({
    mutationFn: (a: Partial<AdCampaign>) => api.saveAdCampaign(a),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setAdForm(null); },
  });

  const q = useQuery<PageResult<Coupon | Campaign | PushMessage | Referral | AdSlot | AdCampaign | AdDelivery>>({
    queryKey: ["mkt", tab, page, keyword],
    queryFn: () =>
      tab === "coupons" ? api.listCoupons({ page, size: SIZE, keyword })
      : tab === "campaigns" ? api.listCampaigns({ page, size: SIZE, keyword })
      : tab === "push" ? api.listPushMessages({ page, size: SIZE, keyword })
      : tab === "referral" ? api.listReferrals({ page, size: SIZE, keyword })
      : tab === "ad-slots" ? api.listAdSlots({ page, size: SIZE, keyword })
      : tab === "ad-campaigns" ? api.listAdCampaigns({ page, size: SIZE, keyword })
      : api.listAdDeliveries({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const couponCols: Column<Coupon>[] = [
    { header: "券号", cell: (c) => <span className="font-medium">{c.couponNo}</span> },
    { header: "名称", cell: (c) => c.name },
    { header: "类型", cell: (c) => <Badge tone="outline">{c.type === "CUT" ? "立减" : "折扣"}</Badge> },
    { header: "面额", cell: (c) => c.type === "CUT" ? `AED ${c.value}` : `${c.value} 折` },
    { header: "门槛", cell: (c) => c.threshold ? `满 ${c.threshold}` : "无" },
    { header: "已发/库存", cell: (c) => <span className="tabular-nums">{c.issued}/{c.stock}</span> },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">进行中</Badge> : <Badge tone="muted">暂停</Badge> },
    { header: t("common.actions"), cell: (c) => canEditCoupon ? <Button size="sm" variant="outline" onClick={() => setCouponForm(c)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const campaignCols: Column<Campaign>[] = [
    { header: "活动号", cell: (c) => <span className="font-medium">{c.campaignNo}</span> },
    { header: "名称", cell: (c) => c.name },
    { header: "类型", cell: (c) => <Badge tone="outline">{c.kind}</Badge> },
    { header: "规则", cell: (c) => <span className="text-muted-foreground">{c.rule}</span> },
    { header: "状态", cell: (c) => <Badge tone={c.status === "RUNNING" ? "success" : c.status === "ENDED" ? "muted" : "warning"}>{c.status === "RUNNING" ? "进行中" : c.status === "ENDED" ? "已结束" : "草稿"}</Badge> },
    { header: "开始", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.startAt)}</span> },
    { header: "结束", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.endAt)}</span> },
    { header: t("common.actions"), cell: (c) => canEditCampaign ? <Button size="sm" variant="outline" onClick={() => setCampaignForm(c)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const pushCols: Column<PushMessage>[] = [
    { header: "推送号", cell: (p) => <span className="font-medium">{p.pushNo}</span> },
    { header: "标题", cell: (p) => p.title },
    { header: "渠道", cell: (p) => <Badge tone="outline">{p.channel === "APP_PUSH" ? "App 推送" : "订阅消息"}</Badge> },
    { header: "受众", cell: (p) => <span className="text-muted-foreground">{p.audience}</span> },
    { header: "触达数", cell: (p) => <span className="tabular-nums">{Math.round(p.sentCount)}</span> },
    { header: "状态", cell: (p) => <Badge tone={p.status === "SENT" ? "success" : "muted"}>{p.status === "SENT" ? "已发送" : "草稿"}</Badge> },
    { header: "发送时间", cell: (p) => <span className="text-muted-foreground">{p.status === "SENT" ? fmtTime(p.sentAt) : "-"}</span> },
    { header: t("common.actions"), cell: (p) => canEditPush ? <Button size="sm" variant="outline" onClick={() => setPushForm(p)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const referralCols: Column<Referral>[] = [
    { header: "邀请号", cell: (r) => <span className="font-medium">{r.inviteNo}</span> },
    { header: "邀请人", cell: (r) => r.inviter },
    { header: "受邀人", cell: (r) => r.invitee },
    { header: "奖励", cell: (r) => <span className="tabular-nums">{money(r.reward, r.currency)}</span> },
    { header: "状态", cell: (r) => <Badge tone={r.status === "REWARDED" ? "success" : "warning"}>{r.status === "REWARDED" ? "已发奖" : "待发奖"}</Badge> },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  const slotCols: Column<AdSlot>[] = [
    { header: "广告位号", cell: (s) => <span className="font-medium">{s.slotNo}</span> },
    { header: "机柜", cell: (s) => <span className="text-muted-foreground">{s.cabinetNo}</span> },
    { header: "位置", cell: (s) => <Badge tone="outline">{s.position === "SCREEN" ? "屏幕" : "机身"}</Badge> },
    { header: "尺寸", cell: (s) => s.size },
    { header: "状态", cell: (s) => <Badge tone={s.status === "OCCUPIED" ? "default" : "muted"}>{s.status === "OCCUPIED" ? "已占用" : "空闲"}</Badge> },
    { header: "创建时间", cell: (s) => <span className="text-muted-foreground">{fmtTime(s.createdAt)}</span> },
    { header: t("common.actions"), cell: (s) => canEditAd ? <Button size="sm" variant="outline" onClick={() => setSlotForm(s)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const adCampaignCols: Column<AdCampaign>[] = [
    { header: "广告号", cell: (a) => <span className="font-medium">{a.adNo}</span> },
    { header: "广告主", cell: (a) => a.advertiser },
    { header: "创意", cell: (a) => <span className="text-muted-foreground">{a.creative}</span> },
    { header: "定向", cell: (a) => <span className="text-muted-foreground">{a.targeting}</span> },
    { header: "状态", cell: (a) => <Badge tone={a.status === "RUNNING" ? "success" : a.status === "ENDED" ? "muted" : "warning"}>{a.status === "RUNNING" ? "投放中" : a.status === "ENDED" ? "已结束" : "草稿"}</Badge> },
    { header: "开始", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.startAt)}</span> },
    { header: "结束", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.endAt)}</span> },
    { header: t("common.actions"), cell: (a) => canEditAd ? <Button size="sm" variant="outline" onClick={() => setAdForm(a)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const deliveryCols: Column<AdDelivery>[] = [
    { header: "投放号", cell: (d) => <span className="font-medium">{d.deliveryNo}</span> },
    { header: "广告号", cell: (d) => <span className="text-muted-foreground">{d.adNo}</span> },
    { header: "广告位", cell: (d) => <span className="text-muted-foreground">{d.slotNo}</span> },
    { header: "曝光", cell: (d) => <span className="tabular-nums">{Math.round(d.impressions)}</span> },
    { header: "播放", cell: (d) => <span className="tabular-nums">{Math.round(d.plays)}</span> },
    { header: "日期", cell: (d) => <span className="text-muted-foreground">{d.date}</span> },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); }} />
      {tab === "coupons" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索券名称"
          onAdd={canEditCoupon ? () => setCouponForm({ type: "CUT", status: "ACTIVE", value: 5, threshold: 0, stock: 1000, issued: 0 }) : undefined}
          addLabel="新增优惠券"
        />
      )}
      {tab === "campaigns" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索活动号/名称/类型"
          onAdd={canEditCampaign ? () => setCampaignForm({ kind: "满减", status: "DRAFT", startAt: "", endAt: "" }) : undefined}
          addLabel="新增活动"
        />
      )}
      {tab === "push" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索推送号/标题/受众"
          onAdd={canEditPush ? () => setPushForm({ channel: "APP_PUSH", status: "DRAFT", audience: "全部用户", sentCount: 0 }) : undefined}
          addLabel="新增推送"
        />
      )}
      {tab === "referral" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索邀请号/邀请人/受邀人"
        />
      )}
      {tab === "ad-slots" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索广告位号/机柜号"
          onAdd={canEditAd ? () => setSlotForm({ position: "SCREEN", status: "IDLE", size: "1080x1920" }) : undefined}
          addLabel="新增广告位"
        />
      )}
      {tab === "ad-campaigns" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索广告号/广告主/创意"
          onAdd={canEditAd ? () => setAdForm({ status: "DRAFT", startAt: "", endAt: "" }) : undefined}
          addLabel="新增广告活动"
        />
      )}
      {tab === "ad-delivery" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索投放号/广告号/广告位"
        />
      )}
      {tab === "coupons" && <DataTable rowKey={(c: Coupon) => c.couponNo} columns={couponCols} rows={q.data?.list as Coupon[]} loading={q.isLoading} />}
      {tab === "campaigns" && <DataTable rowKey={(c: Campaign) => c.campaignNo} columns={campaignCols} rows={q.data?.list as Campaign[]} loading={q.isLoading} />}
      {tab === "push" && <DataTable rowKey={(p: PushMessage) => p.pushNo} columns={pushCols} rows={q.data?.list as PushMessage[]} loading={q.isLoading} />}
      {tab === "referral" && <DataTable rowKey={(r: Referral) => r.inviteNo} columns={referralCols} rows={q.data?.list as Referral[]} loading={q.isLoading} />}
      {tab === "ad-slots" && <DataTable rowKey={(s: AdSlot) => s.slotNo} columns={slotCols} rows={q.data?.list as AdSlot[]} loading={q.isLoading} />}
      {tab === "ad-campaigns" && <DataTable rowKey={(a: AdCampaign) => a.adNo} columns={adCampaignCols} rows={q.data?.list as AdCampaign[]} loading={q.isLoading} />}
      {tab === "ad-delivery" && <DataTable rowKey={(d: AdDelivery) => d.deliveryNo} columns={deliveryCols} rows={q.data?.list as AdDelivery[]} loading={q.isLoading} />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!couponForm}
        onOpenChange={(o) => !o && setCouponForm(null)}
        titleNew="新增优惠券"
        titleEdit={`编辑优惠券 ${couponForm?.couponNo ?? ""}`}
        isEdit={!!couponForm?.couponNo}
        fields={COUPON_FIELDS}
        value={(couponForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCouponForm(v as Partial<Coupon>)}
        onSubmit={() => couponForm && saveCoupon.mutate(couponForm)}
        submitting={saveCoupon.isPending}
      />

      <FormDrawer
        open={!!campaignForm}
        onOpenChange={(o) => !o && setCampaignForm(null)}
        titleNew="新增活动"
        titleEdit={`编辑活动 ${campaignForm?.campaignNo ?? ""}`}
        isEdit={!!campaignForm?.campaignNo}
        fields={CAMPAIGN_FIELDS}
        value={(campaignForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCampaignForm(v as Partial<Campaign>)}
        onSubmit={() => campaignForm && saveCampaign.mutate(campaignForm)}
        submitting={saveCampaign.isPending}
      />

      <FormDrawer
        open={!!pushForm}
        onOpenChange={(o) => !o && setPushForm(null)}
        titleNew="新增推送"
        titleEdit={`编辑推送 ${pushForm?.pushNo ?? ""}`}
        isEdit={!!pushForm?.pushNo}
        fields={PUSH_FIELDS}
        value={(pushForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPushForm(v as Partial<PushMessage>)}
        onSubmit={() => pushForm && savePush.mutate(pushForm)}
        submitting={savePush.isPending}
      />

      <FormDrawer
        open={!!slotForm}
        onOpenChange={(o) => !o && setSlotForm(null)}
        titleNew="新增广告位"
        titleEdit={`编辑广告位 ${slotForm?.slotNo ?? ""}`}
        isEdit={!!slotForm?.slotNo}
        fields={SLOT_FIELDS}
        value={(slotForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setSlotForm(v as Partial<AdSlot>)}
        onSubmit={() => slotForm && saveSlot.mutate(slotForm)}
        submitting={saveSlot.isPending}
      />

      <FormDrawer
        open={!!adForm}
        onOpenChange={(o) => !o && setAdForm(null)}
        titleNew="新增广告活动"
        titleEdit={`编辑广告活动 ${adForm?.adNo ?? ""}`}
        isEdit={!!adForm?.adNo}
        fields={AD_FIELDS}
        value={(adForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setAdForm(v as Partial<AdCampaign>)}
        onSubmit={() => adForm && saveAd.mutate(adForm)}
        submitting={saveAd.isPending}
      />
    </div>
  );
}

export default function MarketingPage() {
  return <Suspense fallback={null}><MarketingInner /></Suspense>;
}
