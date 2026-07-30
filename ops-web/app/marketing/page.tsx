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
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
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
  CouponIssueRecord, AudienceType,
} from "@/lib/types";
import { couponIssuable, couponRemaining, couponExpired, canSendPush } from "@/lib/types";

const SIZE = 10;
const TABS = [
  // 公告管理是营销模块唯一的阶段 1 项（c-app 首页公告条的发布口），故置于首位。
  { key: "notices", label: "公告管理" },
  { key: "coupons", label: "优惠券", phase: 2 as const },
  { key: "coupon-issues", label: "发放记录", phase: 2 as const },
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
  { key: "name", label: "名称", required: true, maxLength: 20, placeholder: "新人立减" },
  { key: "type", label: "类型", type: "select", options: [{ value: "CUT", label: "立减" }, { value: "DISCOUNT", label: "折扣" }] },
  { key: "value", label: "面额 / 折扣", type: "number" },
  { key: "threshold", label: "门槛（满 X 元）", type: "number" },
  { key: "stock", label: "发行总量", type: "number", min: 0, help: "库存上限；发放只增「已发放」，剩余 = 发行总量 - 已发放" },
  { key: "expireAt", label: "有效期止", type: "date", required: true, help: "过期券不可再发放" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "进行中" }, { value: "PAUSED", label: "暂停（已下线，不可发放）" }] },
];

// —— S2 营销投放人群（优惠券发放 / 推送触达共用）——
// 维度全部落在既有主数据上（cUsers / members / consumerSegments），口径见 types/marketing.ts。
const AUD_TYPE_LABEL: Record<AudienceType, string> = {
  ALL: "全体用户", MEMBER_LEVEL: "会员等级", SEGMENT: "消费者分层", USER_LIST: "指定用户号",
};
const MEMBER_LEVEL_OPTS = [
  { value: "SILVER", label: "白银会员" }, { value: "GOLD", label: "黄金会员" }, { value: "PLATINUM", label: "白金会员" },
];
const PUSH_CHANNEL_LABEL: Record<PushMessage["channel"], string> = {
  APP_PUSH: "App 推送", SUBSCRIBE: "订阅消息（站内）", SMS: "短信",
};
const PUSH_STATUS: Record<PushMessage["status"], { label: string; tone: "muted" | "warning" | "success" }> = {
  DRAFT: { label: "草稿", tone: "muted" },
  SCHEDULED: { label: "已排期", tone: "warning" },
  SENDING: { label: "发送中", tone: "warning" },
  SENT: { label: "已发送", tone: "success" },
};
const CAMPAIGN_FIELDS: FieldDef[] = [
  { key: "name", label: "活动名称", placeholder: "夏日充电狂欢" },
  { key: "kind", label: "类型", placeholder: "满减 / 拉新 / 签到" },
  { key: "rule", label: "规则", placeholder: "满 20 减 5" },
  { key: "status", label: "状态", type: "select", options: [{ value: "DRAFT", label: "草稿" }, { value: "RUNNING", label: "进行中" }, { value: "ENDED", label: "已结束" }] },
  { key: "startAt", label: "开始时间", placeholder: "2026-07-01 00:00:00" },
  { key: "endAt", label: "结束时间", placeholder: "2026-07-31 23:59:59" },
];
/**
 * 推送草稿表单。**没有「状态」字段**——状态只能由发送动作的状态机推进
 * （DRAFT →（定时）SCHEDULED → SENDING → SENT），表单能改就等于能伪造「已发送」。
 * 目标人群做成一个扁平下拉（`audienceKey` = `类型:值`），提交时拆回 audienceType/audienceValue。
 */
const pushFields = (segments: { segmentNo: string; segment: string; userCount: number }[]): FieldDef[] => [
  { key: "title", label: "标题", required: true, maxLength: 30, section: "内容", placeholder: "您有一张新券待领取" },
  { key: "content", label: "正文", type: "textarea", rows: 3, required: true, maxLength: 200, section: "内容", placeholder: "现在借充电宝，首单立减 3 AED" },
  {
    key: "channel", label: "渠道", type: "select", required: true, section: "触达设置",
    options: (Object.keys(PUSH_CHANNEL_LABEL) as PushMessage["channel"][]).map((c) => ({ value: c, label: PUSH_CHANNEL_LABEL[c] })),
  },
  {
    key: "audienceKey", label: "目标人群", type: "select", required: true, section: "触达设置",
    help: "人群规模取自会员档案 / 消费者分层，发送时按该规模落目标人数",
    options: [
      { value: "ALL:", label: "全体用户" },
      ...MEMBER_LEVEL_OPTS.map((o) => ({ value: `MEMBER_LEVEL:${o.value}`, label: `会员等级 · ${o.label}` })),
      ...segments.map((s) => ({ value: `SEGMENT:${s.segmentNo}`, label: `消费者分层 · ${s.segment}（${s.userCount} 人）` })),
    ],
  },
];
/** `类型:值` ↔ audienceType/audienceValue 的互转（下拉值只能是一个字符串）。 */
const audKeyOf = (p: Partial<PushMessage>) => `${p.audienceType ?? "ALL"}:${p.audienceValue ?? ""}`;
const parseAudKey = (k: string) => {
  const i = k.indexOf(":");
  return { audienceType: k.slice(0, i) as AudienceType, audienceValue: k.slice(i + 1) };
};
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
  // audienceKey 是表单里的合成字段（`类型:值`），提交时拆回 audienceType/audienceValue
  const [pushForm, setPushForm] = useState<(Partial<PushMessage> & { audienceKey?: string }) | null>(null);
  const [slotForm, setSlotForm] = useState<Partial<AdSlot> | null>(null);
  const [adForm, setAdForm] = useState<Partial<AdCampaign> | null>(null);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);

  // —— S2 优惠券发放 / 推送发送的抽屉状态 ——
  const [issueFor, setIssueFor] = useState<Coupon | null>(null);
  const [issueType, setIssueType] = useState<AudienceType>("ALL");
  const [issueValue, setIssueValue] = useState("");
  const [issueQty, setIssueQty] = useState("100");
  const [sendFor, setSendFor] = useState<PushMessage | null>(null);
  const [sendWhen, setSendWhen] = useState<"NOW" | "SCHEDULED">("NOW");
  const [sendAt, setSendAt] = useState("");
  // 幂等键在抽屉打开时生成一次并全程沿用：双击提交 / 网络重试用的是同一把键，
  // 服务端据此拒绝第二次——这正是「重发必须带幂等键」的落地方式（口径同订单退款）。
  const [sendKey, setSendKey] = useState("");

  const canEditNotice = allow("marketing:coupon:issue");
  const canEditCoupon = allow("marketing:coupon:issue");
  const canIssueCoupon = allow("marketing:coupon:issue");
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

  // —— S2：消费者分层（人群下拉的数据源，取 user 域既有主数据，不另造维度）——
  const segQ = useQuery({
    queryKey: ["mkt-segments"],
    queryFn: () => api.listConsumerSegments({ page: 1, size: 100 }),
    enabled: tab === "coupons" || tab === "push",
  });
  const segments = segQ.data?.list ?? [];

  // —— S2 发放优惠券：库存/状态/有效期由 mock 层兜底，这里只管成功后的失效与提示 ——
  const issueCoupon = useMutation({
    mutationFn: (v: { no: string; targetType: AudienceType; targetValue: string; quantity: number }) =>
      api.issueCoupon(v.no, { targetType: v.targetType, targetValue: v.targetValue, quantity: v.quantity }),
    onSuccess: (r) => {
      notify.success(`已发放 ${r.record.quantity} 张 ${r.record.couponName} · ${r.record.targetDesc} · 剩余 ${couponRemaining(r.coupon)} 张`);
      qc.invalidateQueries({ queryKey: ["mkt"] });
      setIssueFor(null);
    },
  });
  const openIssue = (c: Coupon) => {
    setIssueFor(c);
    setIssueType("ALL");
    setIssueValue("");
    setIssueQty(String(Math.min(100, couponRemaining(c))));
  };
  const issueQtyNum = Number(issueQty);
  const issueQtyOk = Number.isInteger(issueQtyNum) && issueQtyNum > 0 && !!issueFor && issueQtyNum <= couponRemaining(issueFor);
  const issueTargetOk = issueType === "ALL" || !!issueValue.trim();
  /** 发放是批量权益动作（等同于发钱），提交前二次确认，文案写明发给谁、发多少张。 */
  const submitIssue = async () => {
    if (!issueFor || !issueQtyOk || !issueTargetOk) return;
    const audLabel = issueType === "ALL" ? "全体用户"
      : issueType === "MEMBER_LEVEL" ? `${AUD_TYPE_LABEL[issueType]} ${MEMBER_LEVEL_OPTS.find((o) => o.value === issueValue)?.label ?? issueValue}`
      : issueType === "SEGMENT" ? `${AUD_TYPE_LABEL[issueType]} ${segments.find((s) => s.segmentNo === issueValue)?.segment ?? issueValue}`
      : `指定用户号 ${issueValue}`;
    const ok = await confirm({
      title: `发放优惠券 ${issueFor.couponNo}`,
      desc: `将向${audLabel}发放 ${issueQtyNum} 张「${issueFor.name}」，发放后剩余 ${couponRemaining(issueFor) - issueQtyNum} 张。发放不可撤销。`,
      danger: true,
      confirmText: "确认发放",
      cancelText: "再想想",
    });
    if (ok) issueCoupon.mutate({ no: issueFor.couponNo, targetType: issueType, targetValue: issueValue.trim(), quantity: issueQtyNum });
  };

  // —— S2 发送推送：状态机 + 幂等键都在 mock 层强制 ——
  const sendPush = useMutation({
    mutationFn: (v: { no: string; idempotencyKey: string; scheduledAt: string | null }) =>
      api.sendPushMessage(v.no, { idempotencyKey: v.idempotencyKey, scheduledAt: v.scheduledAt }),
    onSuccess: (r) => {
      notify.success(r.status === "SCHEDULED"
        ? `已排期 ${fmtTime(r.scheduledAt ?? "")} 发送 · 预计触达 ${r.targetCount} 人`
        : `已发送 · 目标 ${r.targetCount} 人 / 成功 ${r.successCount} 人`);
      qc.invalidateQueries({ queryKey: ["mkt"] });
      setSendFor(null);
    },
  });
  const openSend = (p: PushMessage) => {
    setSendFor(p);
    setSendWhen("NOW");
    setSendAt("");
    setSendKey(`PSH-${p.pushNo}-${Date.now()}`);
  };
  const submitSend = async () => {
    if (!sendFor) return;
    if (sendWhen === "SCHEDULED" && !sendAt) return;
    const ok = await confirm({
      title: `发送推送 ${sendFor.pushNo}`,
      desc: sendWhen === "NOW"
        ? `将向${sendFor.audience}发送「${sendFor.title}」（${PUSH_CHANNEL_LABEL[sendFor.channel]}）。发出后不可撤回。`
        : `将于 ${sendAt.replace("T", " ")} 向${sendFor.audience}发送「${sendFor.title}」（${PUSH_CHANNEL_LABEL[sendFor.channel]}）。`,
      danger: true,
      confirmText: sendWhen === "NOW" ? "确认发送" : "确认排期",
      cancelText: "再想想",
    });
    if (ok) {
      sendPush.mutate({
        no: sendFor.pushNo, idempotencyKey: sendKey,
        scheduledAt: sendWhen === "SCHEDULED" ? new Date(sendAt).toISOString() : null,
      });
    }
  };
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

  const q = useQuery<PageResult<Notice | Coupon | CouponIssueRecord | Campaign | PushMessage | Referral | AdSlot | AdCampaign | AdDelivery>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["mkt", tab, page, keyword, showArchived],
    queryFn: () =>
      tab === "notices" ? api.listNotices({ page, size: SIZE, keyword, showArchived })
      : tab === "coupons" ? api.listCoupons({ page, size: SIZE, keyword, showArchived })
      : tab === "coupon-issues" ? api.listCouponIssueRecords({ page, size: SIZE, keyword })
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
    // 已发 / 发行总量 + 剩余：发放要看的就是剩余，别让人在脑子里做减法
    { header: "已发/总量", cell: (c) => <span className="tabular-nums">{c.issued}/{c.stock}</span> },
    { header: "剩余", cell: (c) => <span className="tabular-nums">{couponRemaining(c)}</span> },
    {
      header: "有效期止",
      cell: (c) => couponExpired(c)
        ? <Badge tone="muted">已过期 {fmtTime(c.expireAt)}</Badge>
        : <span className="text-muted-foreground">{fmtTime(c.expireAt)}</span>,
    },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">进行中</Badge> : <Badge tone="muted">已下线</Badge> },
    ...archivedCol<Coupon>(),
    {
      header: t("common.actions"),
      cell: (c) => (
        <ArchiveActions
          archived={!!c.archivedAt}
          canWrite={canEditCoupon}
          onArchive={() => askArchiveCoupon(c)}
          onUnarchive={() => askUnarchiveCoupon(c)}
          actions={
            <>
              <Button size="sm" variant="outline" onClick={() => setCouponForm(c)}>{t("common.edit")}</Button>
              {/* 已下线 / 已过期 / 无剩余库存的券**不出**发放按钮（与 mock 层同一个判定函数） */}
              {canIssueCoupon && couponIssuable(c) && (
                <Button size="sm" onClick={() => openIssue(c)}>发放</Button>
              )}
            </>
          }
        />
      ),
    },
  ];

  // 发放记录：发放是不可撤销的权益动作，必须逐笔可查（谁、向谁、发了多少）
  const issueCols: Column<CouponIssueRecord>[] = [
    { header: "发放号", cell: (r) => <span className="font-medium">{r.issueNo}</span> },
    { header: "券号", cell: (r) => <span className="text-muted-foreground">{r.couponNo}</span> },
    { header: "券名称", cell: (r) => r.couponName },
    { header: "发放对象", cell: (r) => <Badge tone="outline">{AUD_TYPE_LABEL[r.targetType]}</Badge> },
    { header: "人群口径", cell: (r) => <span className="text-muted-foreground">{r.targetDesc}</span> },
    { header: "张数", cell: (r) => <span className="tabular-nums">{r.quantity}</span> },
    { header: "操作人", cell: (r) => r.operatorName },
    { header: "发放时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
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
    { header: "渠道", cell: (p) => <Badge tone="outline">{PUSH_CHANNEL_LABEL[p.channel]}</Badge> },
    { header: "目标人群", cell: (p) => <span className="text-muted-foreground">{p.audience}</span> },
    // 目标 / 成功分两列：只看「触达数」看不出失败了多少（关推送权限、停机、黑名单）
    { header: "目标/成功", cell: (p) => <span className="tabular-nums">{p.targetCount}/{p.successCount}</span> },
    { header: "状态", cell: (p) => <Badge tone={PUSH_STATUS[p.status].tone}>{PUSH_STATUS[p.status].label}</Badge> },
    {
      header: "发送时间",
      cell: (p) => <span className="text-muted-foreground">
        {p.status === "SENT" ? fmtTime(p.sentAt) : p.status === "SCHEDULED" ? `排期 ${fmtTime(p.scheduledAt ?? "")}` : "-"}
      </span>,
    },
    {
      header: t("common.actions"),
      cell: (p) => canEditPush ? (
        <div className="flex gap-2">
          {/* 已发送是终态：不出「发送」按钮，也不允许编辑内容（改了等于篡改已发出的消息） */}
          {p.status === "DRAFT" && <Button size="sm" variant="outline" onClick={() => setPushForm({ ...p, audienceKey: audKeyOf(p) })}>{t("common.edit")}</Button>}
          {canSendPush(p.status) && <Button size="sm" onClick={() => openSend(p)}>发送</Button>}
          {p.status === "SENT" && <span className="text-muted-foreground">已发送</span>}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
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
          onAdd={canEditCoupon ? () => setCouponForm({ type: "CUT", status: "ACTIVE", value: 5, threshold: 0, stock: 1000, issued: 0, expireAt: "" }) : undefined}
          addLabel="新增优惠券"
          onExport={onExportOf<Coupon>("优惠券", [
            { header: "券号", value: (c) => c.couponNo },
            { header: "名称", value: (c) => c.name },
            { header: "类型", value: (c) => (c.type === "CUT" ? "立减" : "折扣") },
            { header: "面额", value: (c) => couponAmount(c) },
            { header: "门槛", value: (c) => (c.threshold ? `满 ${money(c.threshold)}` : "无") },
            { header: "已发/总量", value: (c) => `${c.issued}/${c.stock}` },
            { header: "剩余", value: (c) => couponRemaining(c) },
            { header: "有效期止", value: (c) => fmtTime(c.expireAt) },
            { header: "状态", value: (c) => (c.status === "ACTIVE" ? "进行中" : "已下线") },
            ...archivedCsv<Coupon>(),
          ])}
        >
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "coupon-issues" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索发放号/券号/券名称/人群/操作人"
          onExport={onExportOf<CouponIssueRecord>("优惠券发放记录", [
            { header: "发放号", value: (r) => r.issueNo },
            { header: "券号", value: (r) => r.couponNo },
            { header: "券名称", value: (r) => r.couponName },
            { header: "发放对象", value: (r) => AUD_TYPE_LABEL[r.targetType] },
            { header: "人群口径", value: (r) => r.targetDesc },
            { header: "张数", value: (r) => r.quantity },
            { header: "操作人", value: (r) => r.operatorName },
            { header: "发放时间", value: (r) => fmtTime(r.createdAt) },
          ])}
        />
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
          onAdd={canEditPush ? () => setPushForm({ title: "", content: "", channel: "APP_PUSH", audienceKey: "ALL:" }) : undefined}
          addLabel="新增推送"
          onExport={onExportOf<PushMessage>("推送触达", [
            { header: "推送号", value: (p) => p.pushNo },
            { header: "标题", value: (p) => p.title },
            { header: "正文", value: (p) => p.content },
            { header: "渠道", value: (p) => PUSH_CHANNEL_LABEL[p.channel] },
            { header: "目标人群", value: (p) => p.audience },
            { header: "目标人数", value: (p) => p.targetCount },
            { header: "成功人数", value: (p) => p.successCount },
            { header: "状态", value: (p) => PUSH_STATUS[p.status].label },
            { header: "发送时间", value: (p) => (p.status === "SENT" ? fmtTime(p.sentAt) : p.status === "SCHEDULED" ? fmtTime(p.scheduledAt ?? "") : "-") },
            { header: "幂等键", value: (p) => p.idempotencyKey ?? "-" },
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
      {tab === "coupon-issues" && <DataTable rowKey={(r: CouponIssueRecord) => r.issueNo} columns={issueCols} rows={q.data?.list as CouponIssueRecord[]} loading={q.isLoading} empty="暂无发放记录——到「优惠券」tab 选一张在用的券点「发放」，这里会逐笔留痕。" />}
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
        titleEdit={`编辑推送草稿 ${pushForm?.pushNo ?? ""}`}
        isEdit={!!pushForm?.pushNo}
        fields={pushFields(segments)}
        value={(pushForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPushForm(v as Partial<PushMessage> & { audienceKey?: string })}
        onSubmit={() => {
          if (!pushForm) return;
          // 只提交草稿字段：audienceKey 拆回 audienceType/audienceValue，状态由发送动作推进
          const { audienceKey, ...rest } = pushForm;
          savePush.mutate({ ...rest, ...parseAudKey(audienceKey ?? "ALL:") });
        }}
        submitting={savePush.isPending}
      />

      {/* S2 发放优惠券抽屉：人群 + 张数；提交前再走 useConfirm（批量权益动作 = 发钱） */}
      <Drawer
        open={!!issueFor}
        onOpenChange={(o) => !o && setIssueFor(null)}
        title={issueFor ? `发放优惠券 ${issueFor.couponNo}` : ""}
        desc="发放只增「已发放」，剩余随之减少；已下线 / 已过期 / 无剩余的券发不出去"
        footer={
          issueFor && (
            <Button disabled={issueCoupon.isPending || !issueQtyOk || !issueTargetOk} onClick={submitIssue}>
              下一步：确认发放
            </Button>
          )
        }
      >
        {issueFor && (
          <>
            <Field label="券">{issueFor.name} · {couponAmount(issueFor)}{issueFor.threshold ? ` · 满 ${money(issueFor.threshold)}` : ""}</Field>
            <Field label="库存">
              发行总量 {issueFor.stock} · 已发放 {issueFor.issued} · <span className="font-medium">剩余 {couponRemaining(issueFor)}</span>
            </Field>
            <Field label="有效期止">{fmtTime(issueFor.expireAt)}</Field>
            <Field label="发放对象（必选）">
              <Select
                className="w-full"
                value={issueType}
                onChange={(e) => { setIssueType(e.target.value as AudienceType); setIssueValue(""); }}
              >
                {(Object.keys(AUD_TYPE_LABEL) as AudienceType[]).map((k) => (
                  <option key={k} value={k}>{AUD_TYPE_LABEL[k]}</option>
                ))}
              </Select>
            </Field>
            {issueType === "MEMBER_LEVEL" && (
              <Field label="会员等级（必选）">
                <Select className="w-full" value={issueValue} onChange={(e) => setIssueValue(e.target.value)}>
                  <option value="">请选择</option>
                  {MEMBER_LEVEL_OPTS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </Select>
              </Field>
            )}
            {issueType === "SEGMENT" && (
              <Field label="消费者分层（必选）">
                <Select className="w-full" value={issueValue} onChange={(e) => setIssueValue(e.target.value)}>
                  <option value="">请选择</option>
                  {segments.map((s) => <option key={s.segmentNo} value={s.segmentNo}>{s.segment}（{s.userCount} 人）</option>)}
                </Select>
              </Field>
            )}
            {issueType === "USER_LIST" && (
              <Field label="用户号列表（必填，逗号分隔）">
                <Input value={issueValue} placeholder="U3000,U3001,U3002" onChange={(e) => setIssueValue(e.target.value)} />
              </Field>
            )}
            <Field label={`发放张数（必填，≤ 剩余 ${couponRemaining(issueFor)}）`}>
              <Input type="number" min="1" step="1" value={issueQty} onChange={(e) => setIssueQty(e.target.value)} />
            </Field>
            {!issueQtyOk && issueQty !== "" && (
              <div className="rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
                张数须为正整数且不超过剩余库存 {couponRemaining(issueFor)}——超发等于凭空印券。
              </div>
            )}
          </>
        )}
      </Drawer>

      {/* S2 发送推送抽屉：立即 / 定时；幂等键随抽屉生成，重复提交由服务端拒绝 */}
      <Drawer
        open={!!sendFor}
        onOpenChange={(o) => !o && setSendFor(null)}
        title={sendFor ? `发送推送 ${sendFor.pushNo}` : ""}
        desc="状态机：草稿 →（定时）已排期 → 发送中 → 已发送；已发送是终态，不可重发"
        footer={
          sendFor && (
            <Button
              disabled={sendPush.isPending || (sendWhen === "SCHEDULED" && !sendAt)}
              onClick={submitSend}
            >{sendWhen === "NOW" ? "下一步：确认发送" : "下一步：确认排期"}</Button>
          )
        }
      >
        {sendFor && (
          <>
            <Field label="标题">{sendFor.title}</Field>
            <Field label="正文">{sendFor.content || <span className="text-muted-foreground">（空——发送前请先补内容）</span>}</Field>
            <Field label="渠道"><Badge tone="outline">{PUSH_CHANNEL_LABEL[sendFor.channel]}</Badge></Field>
            <Field label="目标人群">{sendFor.audience}</Field>
            <Field label="当前状态"><Badge tone={PUSH_STATUS[sendFor.status].tone}>{PUSH_STATUS[sendFor.status].label}</Badge></Field>
            <Field label="发送时机">
              <Select className="w-full" value={sendWhen} onChange={(e) => setSendWhen(e.target.value as "NOW" | "SCHEDULED")}>
                <option value="NOW">立即发送</option>
                <option value="SCHEDULED">定时发送</option>
              </Select>
            </Field>
            {sendWhen === "SCHEDULED" && (
              <Field label="发送时间（必填）">
                <Input type="datetime-local" value={sendAt} onChange={(e) => setSendAt(e.target.value)} />
              </Field>
            )}
            <Field label="幂等键">
              <span className="text-muted-foreground tabular-nums">{sendKey}</span>
            </Field>
            <div className="rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
              本次提交携带上面这把幂等键，重复提交（双击 / 重试）服务端会直接拒绝——触达重复提交等于把消息真发两遍。
            </div>
          </>
        )}
      </Drawer>

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
