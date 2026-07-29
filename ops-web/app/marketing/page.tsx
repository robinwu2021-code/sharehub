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
import { isPhaseLocked } from "@/lib/phase";
import { notify } from "@/lib/notify";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import type {
  Notice, Coupon, Campaign, PushMessage, Referral, AdSlot, AdCampaign, AdDelivery, PageResult,
} from "@/lib/types";

const SIZE = 10;
const TABS = [
  // 公告管理是营销模块唯一的阶段 1 项（c-app 首页公告条的发布口），故置于首位。
  { key: "notices", label: "公告管理" },
  { key: "coupons", label: "优惠券", phase: 2 as const },
  { key: "campaigns", label: "活动", phase: 2 as const },
  { key: "push", label: "推送触达", phase: 3 as const },
  { key: "referral", label: "邀请裂变", phase: 3 as const },
  { key: "ad-slots", label: "广告位", phase: 3 as const },
  { key: "ad-campaigns", label: "广告活动", phase: 3 as const },
  { key: "ad-delivery", label: "投放与曝光", phase: 3 as const },
];
// 三语（zh/en/ar）+ 生效期 + 置顶：竞品公告只有单语，我们要覆盖 MENA 多语市场。
// B0 组件能力的样板用法：分区 section + 三语 textarea + date + required/maxLength 校验。
// 新页面照此写，勿再手搓控件（见 TDD-运营端前端补全方案 §八-1）。
const NOTICE_FIELDS: FieldDef[] = [
  { key: "noticeNo", label: "公告号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "基本信息" },
  { key: "type", label: "类型", type: "select", required: true, section: "基本信息", options: [{ value: "SYSTEM", label: "系统公告" }, { value: "PROMO", label: "活动公告" }, { value: "MAINTENANCE", label: "维护公告" }] },
  { key: "pinned", label: "置顶", type: "switch", section: "基本信息", help: "置顶公告在 C 端首页公告条优先展示" },
  { key: "title", label: "标题（中文）", required: true, maxLength: 40, section: "三语内容", placeholder: "斋月期间机柜服务时间调整" },
  { key: "titleEn", label: "标题（English）", maxLength: 60, section: "三语内容", placeholder: "Ramadan service hours update" },
  { key: "titleAr", label: "标题（العربية）", maxLength: 60, section: "三语内容", placeholder: "تحديث ساعات الخدمة خلال رمضان" },
  { key: "content", label: "正文（中文）", type: "textarea", rows: 3, required: true, maxLength: 200, section: "三语内容", placeholder: "面向 C 端首页公告条展示的正文" },
  { key: "contentEn", label: "正文（English）", type: "textarea", rows: 3, maxLength: 300, section: "三语内容", placeholder: "Body shown in the C-end home banner" },
  { key: "contentAr", label: "正文（العربية）", type: "textarea", rows: 3, maxLength: 300, section: "三语内容", placeholder: "النص المعروض في شريط الإعلانات" },
  { key: "startAt", label: "生效开始", type: "date", required: true, section: "发布控制" },
  { key: "endAt", label: "生效结束", type: "date", section: "发布控制", help: "留空表示长期有效" },
  { key: "status", label: "状态", type: "select", required: true, section: "发布控制", options: [{ value: "DRAFT", label: "草稿" }, { value: "PUBLISHED", label: "已发布" }, { value: "OFFLINE", label: "已下线" }] },
  { key: "publishedBy", label: "发布人", section: "发布控制", placeholder: "运营中心" },
];
const NOTICE_TYPE: Record<Notice["type"], { label: string; tone: "outline" | "success" | "warning" }> = {
  SYSTEM: { label: "系统公告", tone: "outline" },
  PROMO: { label: "活动公告", tone: "success" },
  MAINTENANCE: { label: "维护公告", tone: "warning" },
};
const NOTICE_STATUS: Record<Notice["status"], { label: string; tone: "muted" | "success" }> = {
  DRAFT: { label: "草稿", tone: "muted" },
  PUBLISHED: { label: "已发布", tone: "success" },
  OFFLINE: { label: "已下线", tone: "muted" },
};

// 默认 tab：阶段 1 下「优惠券」被屏蔽，落到唯一可见的 P1 项「公告管理」；
// 放开阶段 2 后 /marketing（nav 中标为「优惠券」）恢复原语义。
const DEFAULT_TAB = isPhaseLocked(2) ? "notices" : "coupons";

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
  const { confirm, dialog } = useConfirm();
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : DEFAULT_TAB);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位，避免在别的 tab 残留看不见的过滤态。
  const [showArchived, setShowArchived] = useState(false);
  const [noticeForm, setNoticeForm] = useState<Partial<Notice> | null>(null);
  const [couponForm, setCouponForm] = useState<Partial<Coupon> | null>(null);
  const [campaignForm, setCampaignForm] = useState<Partial<Campaign> | null>(null);
  const [pushForm, setPushForm] = useState<Partial<PushMessage> | null>(null);
  const [slotForm, setSlotForm] = useState<Partial<AdSlot> | null>(null);
  const [adForm, setAdForm] = useState<Partial<AdCampaign> | null>(null);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);

  const canEditNotice = allow("marketing:coupon:issue");
  const canEditCoupon = allow("marketing:coupon:issue");
  const canEditCampaign = allow("marketing:campaign:manage");
  const canEditPush = allow("marketing:push:send");
  const canEditAd = allow("marketing:ad:manage");
  const saveNotice = useMutation({
    mutationFn: (n: Partial<Notice>) => api.saveNotice(n),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setNoticeForm(null); },
  });
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

  // 归档 / 恢复：错误由全局 MutationCache 接管，这里只管成功后的失效与提示。
  const archiveNoticeM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveNotice(v.no) : api.archiveNotice(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  const archiveCouponM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveCoupon(v.no) : api.archiveCoupon(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  // 公告 / 优惠券非主数据，归档确认不要求手输编号（requireText 只留给机柜/站点/角色等）。
  const askArchiveNotice = async (n: Notice) => {
    if (await confirm(archiveConfirm("公告", n.noticeNo))) archiveNoticeM.mutate({ no: n.noticeNo, undo: false });
  };
  const askUnarchiveNotice = async (n: Notice) => {
    if (await confirm(unarchiveConfirm("公告", n.noticeNo))) archiveNoticeM.mutate({ no: n.noticeNo, undo: true });
  };
  const askArchiveCoupon = async (c: Coupon) => {
    if (await confirm(archiveConfirm("优惠券", c.couponNo))) archiveCouponM.mutate({ no: c.couponNo, undo: false });
  };
  const askUnarchiveCoupon = async (c: Coupon) => {
    if (await confirm(unarchiveConfirm("优惠券", c.couponNo))) archiveCouponM.mutate({ no: c.couponNo, undo: true });
  };

  const q = useQuery<PageResult<Notice | Coupon | Campaign | PushMessage | Referral | AdSlot | AdCampaign | AdDelivery>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["mkt", tab, page, keyword, showArchived],
    queryFn: () =>
      tab === "notices" ? api.listNotices({ page, size: SIZE, keyword, showArchived })
      : tab === "coupons" ? api.listCoupons({ page, size: SIZE, keyword, showArchived })
      : tab === "campaigns" ? api.listCampaigns({ page, size: SIZE, keyword })
      : tab === "push" ? api.listPushMessages({ page, size: SIZE, keyword })
      : tab === "referral" ? api.listReferrals({ page, size: SIZE, keyword })
      : tab === "ad-slots" ? api.listAdSlots({ page, size: SIZE, keyword })
      : tab === "ad-campaigns" ? api.listAdCampaigns({ page, size: SIZE, keyword })
      : api.listAdDeliveries({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  // 归档时间列只在「显示已归档」打开时插入：默认视图里它整列都是 "-"，白占宽度。
  // 位置固定在操作列之前，保证操作列仍是最右（§3.2 操作列固定最右）。
  const archivedCol = <T extends { archivedAt: string | null }>(): Column<T>[] =>
    showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];

  const couponAmount = (c: Coupon) => c.type === "CUT" ? money(c.value) : `${c.value} 折`;

  const noticeCols: Column<Notice>[] = [
    { header: "公告号", cell: (n) => <span className="font-medium">{n.noticeNo}</span> },
    { header: "标题（中）", cell: (n) => n.title },
    { header: "类型", cell: (n) => <Badge tone={NOTICE_TYPE[n.type].tone}>{NOTICE_TYPE[n.type].label}</Badge> },
    { header: "置顶", cell: (n) => n.pinned ? <Badge tone="success">置顶</Badge> : <span className="text-muted-foreground">-</span> },
    { header: "生效期", cell: (n) => <span className="text-muted-foreground">{fmtTime(n.startAt)} ~ {fmtTime(n.endAt)}</span> },
    { header: "状态", cell: (n) => <Badge tone={NOTICE_STATUS[n.status].tone}>{NOTICE_STATUS[n.status].label}</Badge> },
    ...archivedCol<Notice>(),
    {
      header: t("common.actions"),
      cell: (n) => (
        <ArchiveActions
          archived={!!n.archivedAt}
          canWrite={canEditNotice}
          onArchive={() => askArchiveNotice(n)}
          onUnarchive={() => askUnarchiveNotice(n)}
          actions={<Button size="sm" variant="outline" onClick={() => setNoticeForm(n)}>{t("common.edit")}</Button>}
        />
      ),
    },
  ];

  const couponCols: Column<Coupon>[] = [
    { header: "券号", cell: (c) => <span className="font-medium">{c.couponNo}</span> },
    { header: "名称", cell: (c) => c.name },
    { header: "类型", cell: (c) => <Badge tone="outline">{c.type === "CUT" ? "立减" : "折扣"}</Badge> },
    { header: "面额", cell: (c) => couponAmount(c) },
    { header: "门槛", cell: (c) => c.threshold ? `满 ${money(c.threshold)}` : "无" },
    { header: "已发/库存", cell: (c) => <span className="tabular-nums">{c.issued}/{c.stock}</span> },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">进行中</Badge> : <Badge tone="muted">暂停</Badge> },
    ...archivedCol<Coupon>(),
    {
      header: t("common.actions"),
      cell: (c) => (
        <ArchiveActions
          archived={!!c.archivedAt}
          canWrite={canEditCoupon}
          onArchive={() => askArchiveCoupon(c)}
          onUnarchive={() => askUnarchiveCoupon(c)}
          actions={<Button size="sm" variant="outline" onClick={() => setCouponForm(c)}>{t("common.edit")}</Button>}
        />
      ),
    },
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

  // 导出当页数据（§10.2）。列与表格可见列一致，故归档时间同样只在开关打开时进 CSV。
  const onExportOf = <T,>(name: string, cols: CsvColumn<T>[]) =>
    () => exportCsv<T>(name, cols, (q.data?.list ?? []) as T[]);
  const archivedCsv = <T extends { archivedAt: string | null }>(): CsvColumn<T>[] =>
    showArchived ? [{ header: "归档时间", value: (r: T) => r.archivedAt ? fmtTime(r.archivedAt) : "-" }] : [];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setShowArchived(false); }} />
      {tab === "notices" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索公告号/标题（中/英/阿）/发布人"
          onAdd={canEditNotice ? () => setNoticeForm({ type: "SYSTEM", pinned: false, status: "DRAFT", title: "", titleEn: "", titleAr: "", content: "", contentEn: "", contentAr: "", startAt: "", endAt: "", publishedBy: "" }) : undefined}
          addLabel="新增公告"
          // B0 样板：前端 CSV 导出当页数据（决策 §八-2）。exportCsv 自带 UTF-8 BOM 防 Excel 乱码。
          onExport={onExportOf<Notice>("公告管理", [
            { header: "公告号", value: (n) => n.noticeNo },
            { header: "标题（中）", value: (n) => n.title },
            { header: "标题（EN）", value: (n) => n.titleEn },
            { header: "标题（AR）", value: (n) => n.titleAr },
            { header: "类型", value: (n) => NOTICE_TYPE[n.type].label },
            { header: "置顶", value: (n) => (n.pinned ? "是" : "否") },
            { header: "生效开始", value: (n) => n.startAt },
            { header: "生效结束", value: (n) => n.endAt },
            { header: "状态", value: (n) => NOTICE_STATUS[n.status].label },
            { header: "发布人", value: (n) => n.publishedBy },
            ...archivedCsv<Notice>(),
          ])}
        >
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "coupons" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索券名称"
          onAdd={canEditCoupon ? () => setCouponForm({ type: "CUT", status: "ACTIVE", value: 5, threshold: 0, stock: 1000, issued: 0 }) : undefined}
          addLabel="新增优惠券"
          onExport={onExportOf<Coupon>("优惠券", [
            { header: "券号", value: (c) => c.couponNo },
            { header: "名称", value: (c) => c.name },
            { header: "类型", value: (c) => (c.type === "CUT" ? "立减" : "折扣") },
            { header: "面额", value: (c) => couponAmount(c) },
            { header: "门槛", value: (c) => (c.threshold ? `满 ${money(c.threshold)}` : "无") },
            { header: "已发/库存", value: (c) => `${c.issued}/${c.stock}` },
            { header: "状态", value: (c) => (c.status === "ACTIVE" ? "进行中" : "暂停") },
            ...archivedCsv<Coupon>(),
          ])}
        >
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "campaigns" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索活动号/名称/类型"
          onAdd={canEditCampaign ? () => setCampaignForm({ kind: "满减", status: "DRAFT", startAt: "", endAt: "" }) : undefined}
          addLabel="新增活动"
          onExport={onExportOf<Campaign>("活动", [
            { header: "活动号", value: (c) => c.campaignNo },
            { header: "名称", value: (c) => c.name },
            { header: "类型", value: (c) => c.kind },
            { header: "规则", value: (c) => c.rule },
            { header: "状态", value: (c) => (c.status === "RUNNING" ? "进行中" : c.status === "ENDED" ? "已结束" : "草稿") },
            { header: "开始", value: (c) => fmtTime(c.startAt) },
            { header: "结束", value: (c) => fmtTime(c.endAt) },
          ])}
        />
      )}
      {tab === "push" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索推送号/标题/受众"
          onAdd={canEditPush ? () => setPushForm({ channel: "APP_PUSH", status: "DRAFT", audience: "全部用户", sentCount: 0 }) : undefined}
          addLabel="新增推送"
          onExport={onExportOf<PushMessage>("推送触达", [
            { header: "推送号", value: (p) => p.pushNo },
            { header: "标题", value: (p) => p.title },
            { header: "渠道", value: (p) => (p.channel === "APP_PUSH" ? "App 推送" : "订阅消息") },
            { header: "受众", value: (p) => p.audience },
            { header: "触达数", value: (p) => Math.round(p.sentCount) },
            { header: "状态", value: (p) => (p.status === "SENT" ? "已发送" : "草稿") },
            { header: "发送时间", value: (p) => (p.status === "SENT" ? fmtTime(p.sentAt) : "-") },
          ])}
        />
      )}
      {tab === "referral" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索邀请号/邀请人/受邀人"
          onExport={onExportOf<Referral>("邀请裂变", [
            { header: "邀请号", value: (r) => r.inviteNo },
            { header: "邀请人", value: (r) => r.inviter },
            { header: "受邀人", value: (r) => r.invitee },
            { header: "奖励", value: (r) => money(r.reward, r.currency) },
            { header: "状态", value: (r) => (r.status === "REWARDED" ? "已发奖" : "待发奖") },
            { header: "时间", value: (r) => fmtTime(r.createdAt) },
          ])}
        />
      )}
      {tab === "ad-slots" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索广告位号/机柜号"
          onAdd={canEditAd ? () => setSlotForm({ position: "SCREEN", status: "IDLE", size: "1080x1920" }) : undefined}
          addLabel="新增广告位"
          onExport={onExportOf<AdSlot>("广告位", [
            { header: "广告位号", value: (s) => s.slotNo },
            { header: "机柜", value: (s) => s.cabinetNo },
            { header: "位置", value: (s) => (s.position === "SCREEN" ? "屏幕" : "机身") },
            { header: "尺寸", value: (s) => s.size },
            { header: "状态", value: (s) => (s.status === "OCCUPIED" ? "已占用" : "空闲") },
            { header: "创建时间", value: (s) => fmtTime(s.createdAt) },
          ])}
        />
      )}
      {tab === "ad-campaigns" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索广告号/广告主/创意"
          onAdd={canEditAd ? () => setAdForm({ status: "DRAFT", startAt: "", endAt: "" }) : undefined}
          addLabel="新增广告活动"
          onExport={onExportOf<AdCampaign>("广告活动", [
            { header: "广告号", value: (a) => a.adNo },
            { header: "广告主", value: (a) => a.advertiser },
            { header: "创意", value: (a) => a.creative },
            { header: "定向", value: (a) => a.targeting },
            { header: "状态", value: (a) => (a.status === "RUNNING" ? "投放中" : a.status === "ENDED" ? "已结束" : "草稿") },
            { header: "开始", value: (a) => fmtTime(a.startAt) },
            { header: "结束", value: (a) => fmtTime(a.endAt) },
          ])}
        />
      )}
      {tab === "ad-delivery" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索投放号/广告号/广告位"
          onExport={onExportOf<AdDelivery>("投放与曝光", [
            { header: "投放号", value: (d) => d.deliveryNo },
            { header: "广告号", value: (d) => d.adNo },
            { header: "广告位", value: (d) => d.slotNo },
            { header: "曝光", value: (d) => Math.round(d.impressions) },
            { header: "播放", value: (d) => Math.round(d.plays) },
            { header: "日期", value: (d) => d.date },
          ])}
        />
      )}
      {tab === "notices" && <DataTable rowKey={(n: Notice) => n.noticeNo} columns={noticeCols} rows={q.data?.list as Notice[]} loading={q.isLoading} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的公告——换个关键词，或点「新增公告」发布第一条 C 端公告条。" : "暂无在用公告——可能都已归档（打开「显示已归档」查看），或点「新增公告」发布第一条。"} />}
      {tab === "coupons" && <DataTable rowKey={(c: Coupon) => c.couponNo} columns={couponCols} rows={q.data?.list as Coupon[]} loading={q.isLoading} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的优惠券——换个关键词，或点「新增优惠券」建一张。" : "暂无在用优惠券——可能都已归档（打开「显示已归档」查看），或点「新增优惠券」建第一张。"} />}
      {tab === "campaigns" && <DataTable rowKey={(c: Campaign) => c.campaignNo} columns={campaignCols} rows={q.data?.list as Campaign[]} loading={q.isLoading} empty="暂无营销活动——点「新增活动」配置满减 / 拉新 / 签到规则。" />}
      {tab === "push" && <DataTable rowKey={(p: PushMessage) => p.pushNo} columns={pushCols} rows={q.data?.list as PushMessage[]} loading={q.isLoading} empty="暂无推送任务——点「新增推送」创建一条 App 推送或订阅消息。" />}
      {tab === "referral" && <DataTable rowKey={(r: Referral) => r.inviteNo} columns={referralCols} rows={q.data?.list as Referral[]} loading={q.isLoading} empty="暂无邀请记录——用户在 C 端发起邀请后自动生成，无需在此手工录入。" />}
      {tab === "ad-slots" && <DataTable rowKey={(s: AdSlot) => s.slotNo} columns={slotCols} rows={q.data?.list as AdSlot[]} loading={q.isLoading} empty="暂无广告位——点「新增广告位」把机柜屏幕 / 机身登记为可售位。" />}
      {tab === "ad-campaigns" && <DataTable rowKey={(a: AdCampaign) => a.adNo} columns={adCampaignCols} rows={q.data?.list as AdCampaign[]} loading={q.isLoading} empty="暂无广告活动——点「新增广告活动」录入广告主与创意后再排期投放。" />}
      {tab === "ad-delivery" && <DataTable rowKey={(d: AdDelivery) => d.deliveryNo} columns={deliveryCols} rows={q.data?.list as AdDelivery[]} loading={q.isLoading} empty="暂无投放数据——广告活动开始投放后按天回传曝光与播放量。" />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!noticeForm}
        onOpenChange={(o) => !o && setNoticeForm(null)}
        titleNew="新增公告"
        titleEdit={`编辑公告 ${noticeForm?.noticeNo ?? ""}`}
        isEdit={!!noticeForm?.noticeNo}
        fields={NOTICE_FIELDS}
        value={(noticeForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setNoticeForm(v as Partial<Notice>)}
        onSubmit={() => noticeForm && saveNotice.mutate(noticeForm)}
        submitting={saveNotice.isPending}
      />

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

      {dialog}
    </div>
  );
}

export default function MarketingPage() {
  return <Suspense fallback={null}><MarketingInner /></Suspense>;
}
