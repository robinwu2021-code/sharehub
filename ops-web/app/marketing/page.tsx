"use client";

import { Suspense, useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab, keepWithinTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { segmentedTrackClass, segmentedItemClass } from "@/components/ui/segmented";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { Notice as InfoNotice } from "@/components/ui/notice";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import type {
  Coupon, Campaign, PushMessage, Referral, AdSlot, AdCampaign, AdDelivery, PageResult,
  CouponIssueRecord, AudienceType, CampaignAction, AdCampaignAction, ReferralRule,
} from "@/lib/types";
import {
  couponIssuable, couponRemaining, couponExpired, canSendPush,
  CAMPAIGN_TRANSITIONS, campaignActions, campaignWindowPassed,
  AD_CAMPAIGN_TRANSITIONS, adCampaignActions,
  // 曝光按周期筛：与坪效/绩效同一套 REPORT_PERIODS
  REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod,
  canPushAction,
} from "@/lib/types";

/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;
// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
//
// `coupon-issues`（发放记录）是**唯一自带名字的一条**：它在菜单里没有入口，
// 只能从优惠券页内切过去。这不是本次整理造成的，是功能清单的待办
// （要么补进菜单、要么承认它就是个页内子视图）——在定下来之前如实标成子视图，
// 而不是让 navTabs 在开发期抛错把页面打挂。
// 2026-09-23 移除 "notices"：公告管理与「运营管理 › 公告管理」调同一组 API
// （listNotices/saveNotice/archiveNotice），是同一张表的两个维护入口。
// 按「重合功能一律并到运营管理」收敛到那边，本页不再留第二份。
const TAB_KEYS = ["coupons", { key: "coupon-issues", label: "发放记录" },
  "campaigns", "push", "referral", "ad-slots", "ad-campaigns", "ad-delivery"] as const;
// 默认 tab：公告管理并入运营管理后，本页只剩促销与广告，一律落「优惠券」。
// （阶段 1 下它被 phase 屏蔽，页面由 PhaseGuard 兜底，不再需要一个 P1 的替补 tab。）
const DEFAULT_TAB = "coupons";

// 邀请奖励规则表单。`rewardTo` 用单选而不是两个勾选：BOTH 是「一次事件出两笔奖励」，
// 与「二选一」在结算上完全不同，枚举把歧义堵死（见 types/marketing.ts 的注释）。
const REFERRAL_RULE_FIELDS: FieldDef[] = [
  { key: "name", label: "规则名称", required: true, placeholder: "首单奖励", section: "基本" },
  { key: "rewardTo", label: "奖励对象", type: "select", required: true, section: "基本",
    options: [
      { value: "INVITER", label: "仅邀请人" },
      { value: "INVITEE", label: "仅受邀人" },
      { value: "BOTH", label: "双方各得（一次邀请发两笔）" },
    ] },
  { key: "trigger", label: "触发条件", type: "select", required: true, section: "基本",
    options: [
      { value: "REGISTERED", label: "受邀人完成注册" },
      { value: "FIRST_ORDER", label: "受邀人首次下单" },
      { value: "FIRST_PAID", label: "受邀人首次支付成功" },
    ], help: "越靠后越难触发，但获客质量越高" },
  { key: "rewardAmount", label: "单侧奖励金额", type: "number", required: true, section: "金额与上限",
    help: "必须大于 0；「双方各得」时双方各得此额，不是均分" },
  { key: "currency", label: "币种", type: "select", section: "金额与上限",
    options: [{ value: "AED", label: "AED" }, { value: "SAR", label: "SAR" }] },
  { key: "maxPerInviter", label: "每位邀请人上限（次）", type: "number", section: "金额与上限",
    help: "0 = 不限。设上限是防刷的第一道闸" },
  { key: "startAt", label: "生效开始", type: "date", required: true, section: "生效期" },
  { key: "endAt", label: "生效结束", type: "date", required: true, section: "生效期" },
  { key: "status", label: "状态", type: "select", section: "生效期",
    options: [{ value: "ACTIVE", label: "生效" }, { value: "DISABLED", label: "停用" }],
    help: "同一时间窗内只允许一条生效规则——重叠会导致一次邀请发多笔" },
];

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
const PUSH_STATUS: StatusMap<PushMessage["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  SCHEDULED: { label: "已排期", tone: "warning" },
  SENDING: { label: "发送中", tone: "warning" },
  SENT: { label: "已发送", tone: "success" },
};
// 裂变 / 广告位 / 广告投放的状态映射。原先是三处内联 ternary ——
// 同一个枚举在别的页面很可能配出不同颜色，且筛选项文案要另抄一遍。
const REFERRAL_STATUS: StatusMap<Referral["status"]> = {
  PENDING: { label: "待发奖", tone: "warning" },
  REWARDED: { label: "已发奖", tone: "success" },
};
const AD_SLOT_STATUS: StatusMap<AdSlot["status"]> = {
  IDLE: { label: "空闲", tone: "muted" },
  OCCUPIED: { label: "已占用", tone: "default" },
};
const RULE_STATUS: StatusMap<ReferralRule["status"]> = {
  ACTIVE: { label: "生效", tone: "success" },
  DISABLED: { label: "停用", tone: "muted" },
};
const REWARD_TO_LABEL: Record<ReferralRule["rewardTo"], string> = {
  INVITER: "仅邀请人", INVITEE: "仅受邀人", BOTH: "双方各得",
};
const TRIGGER_LABEL: Record<ReferralRule["trigger"], string> = {
  REGISTERED: "完成注册", FIRST_ORDER: "首次下单", FIRST_PAID: "首次支付",
};
const AD_CAMPAIGN_STATUS: StatusMap<AdCampaign["status"]> = {
  DRAFT: { label: "草稿", tone: "warning" },
  RUNNING: { label: "投放中", tone: "success" },
  ENDED: { label: "已结束", tone: "muted" },
};
const CAMPAIGN_STATUS: StatusMap<Campaign["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  RUNNING: { label: "进行中", tone: "success" },
  PAUSED: { label: "已暂停", tone: "warning" },
  ENDED: { label: "已结束", tone: "muted" },
};
/**
 * 活动表单。**没有「状态」字段**——状态只能由启停动作的状态机推进
 * （草稿 → 进行中 ⇄ 已暂停 → 已结束），表单能改就等于能复活已结束的活动。
 */
const CAMPAIGN_FIELDS: FieldDef[] = [
  { key: "name", label: "活动名称", required: true, maxLength: 20, placeholder: "夏日充电狂欢" },
  { key: "kind", label: "类型", placeholder: "满减 / 拉新 / 签到" },
  { key: "rule", label: "规则", placeholder: "满 20 减 5" },
  { key: "startAt", label: "开始时间", placeholder: "2026-07-01 00:00:00" },
  { key: "endAt", label: "结束时间", placeholder: "2026-07-31 23:59:59", help: "结束时间已过的活动不能再启动，需先延长" },
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
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  // 默认 tab 随分期变（阶段 1 是公告、阶段 2 起是优惠券），而 tab 顺序固定 ——
  // 所以要显式告诉 navTabs 哪个是默认，它才知道菜单里不带 query 的那条叶子指的是谁
  const tabs = useNavTabs("/marketing", TAB_KEYS, DEFAULT_TAB);
  const { tab, setTab } = usePageTab(tabs, () => {
    paging.reset(); setKeyword(""); setShowArchived(false); setCampaignStatus("");
  }, { defaultKey: DEFAULT_TAB });
  // 投放曝光周期（缺省近 30 日，同报表域）
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  // 裂变页子视图：邀请记录（只读流水）/ 奖励规则（可配置）
  const [refView, setRefView] = useState<"records" | "rules">("records");
  const [ruleForm, setRuleForm] = useState<Partial<ReferralRule> | null>(null);
  const [keyword, setKeyword] = useState("");
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位，避免在别的 tab 残留看不见的过滤态。
  const [showArchived, setShowArchived] = useState(false);
  // 活动状态筛：加了「暂停」之后，要能把被暂停的活动单独捞出来复核
  const [campaignStatus, setCampaignStatus] = useState("");
  const [couponForm, setCouponForm] = useState<Partial<Coupon> | null>(null);
  const [campaignForm, setCampaignForm] = useState<Partial<Campaign> | null>(null);
  // audienceKey 是表单里的合成字段（`类型:值`），提交时拆回 audienceType/audienceValue
  const [pushForm, setPushForm] = useState<(Partial<PushMessage> & { audienceKey?: string }) | null>(null);
  const [slotForm, setSlotForm] = useState<Partial<AdSlot> | null>(null);
  const [adForm, setAdForm] = useState<Partial<AdCampaign> | null>(null);

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

  const canEditCoupon = allow("marketing:coupon:issue");
  const canIssueCoupon = allow("marketing:coupon:issue");
  // 权限码对齐权限清单与后端（marketing:campaign:read / :update）。原先写的 `:manage`
  // 在清单里根本不存在，只有靠 `marketing:*`（BD/ADMIN）通配才碰巧亮起——等于没做权限控制。
  const canEditCampaign = allow("marketing:campaign:update");
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
  // 启停：合法性（状态机 + 时间窗）由 mock/后端兜底，这里只管二次确认与成功提示。
  const transitionCampaign = useMutation({
    mutationFn: (v: { no: string; action: CampaignAction }) => api.transitionCampaign(v.no, v.action),
    onSuccess: (c, v) => {
      qc.invalidateQueries({ queryKey: ["mkt"] });
      notify.success(`活动 ${c.campaignNo} 已${CAMPAIGN_TRANSITIONS[v.action].label} · 当前${CAMPAIGN_STATUS[c.status].label}`);
    },
  });
  // 广告上线/暂停/下线。状态机与时间窗由 types 的 AD_CAMPAIGN_TRANSITIONS 单点定义，
  // mock 层强制、按钮由它派生 —— 与营销活动分开是因为广告要对广告主结算，语义不同。
  const transitionAd = useMutation({
    mutationFn: (v: { no: string; action: AdCampaignAction }) => api.transitionAdCampaign(v.no, v.action),
    onSuccess: (a, v) => {
      qc.invalidateQueries({ queryKey: ["mkt"] });
      notify.success(`广告 ${a.adNo} 已${AD_CAMPAIGN_TRANSITIONS[v.action].label} · 当前${AD_CAMPAIGN_STATUS[a.status].label}`);
    },
  });
  // 邀请奖励规则：独立查询（子视图切到「奖励规则」才拉）
  const rules = useQuery({
    queryKey: ["mkt-referral-rules", paging.page, paging.size, keyword],
    queryFn: () => api.listReferralRules({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "referral" && refView === "rules",
  });
  // 校验（金额>0 / 窗口有效 / 生效期不重叠）在 mock 层强制，错误由全局 MutationCache 提示
  const saveRule = useMutation({
    mutationFn: (x: Partial<ReferralRule>) => api.saveReferralRule(x),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["mkt-referral-rules"] });
      notify.success(t("common.success"));
      setRuleForm(null);
    },
  });

  /** 上线即开始对广告主计费、素材上柜机屏；下线是终态，按危险动作处理。 */
  const askTransitionAd = async (a: AdCampaign, action: AdCampaignAction) => {
    const label = AD_CAMPAIGN_TRANSITIONS[action].label;
    const desc = action === "launch"
      ? `上线后素材立即进入柜机屏轮播，并开始按曝光对广告主「${a.advertiser}」计费。`
      : action === "pause"
      ? `暂停后素材立即停止轮播、停止计费，可再次上线。`
      : `下线后广告进入终态，不可再上线（需要复投请另建广告）。`;
    const ok = await confirm({
      title: `${label}广告 ${a.adNo}`,
      desc: `${desc}投放窗口：${fmtTime(a.startAt)} ~ ${fmtTime(a.endAt)}。`,
      danger: action === "stop",
      confirmText: `确认${label}`,
      cancelText: "再想想",
    });
    if (ok) transitionAd.mutate({ no: a.adNo, action });
  };

  /** 启停都影响 C 端能不能领到权益，逐个二次确认；「结束」是终态，按危险动作处理。 */
  const askTransitionCampaign = async (c: Campaign, action: CampaignAction) => {
    const label = CAMPAIGN_TRANSITIONS[action].label;
    const desc = action === "start"
      ? `启动后活动规则「${c.rule}」立即对 C 端生效，用户下单即可命中。`
      : action === "pause"
      ? `暂停后 C 端立即不再命中该活动规则，已发生的订单不受影响；之后可再次启动。`
      : `结束后活动进入终态，不可再启动（需要复用请另建活动）。`;
    const ok = await confirm({
      title: `${label}活动 ${c.campaignNo}`,
      desc: `${desc}活动窗口：${fmtTime(c.startAt)} ~ ${fmtTime(c.endAt)}。`,
      danger: action === "end",
      confirmText: `确认${label}`,
      cancelText: "再想想",
    });
    if (ok) transitionCampaign.mutate({ no: c.campaignNo, action });
  };
  const savePush = useMutation({
    mutationFn: (p: Partial<PushMessage>) => api.savePushMessage(p),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(t("common.success")); setPushForm(null); },
  });

  // —— S2：消费者分层（人群下拉的数据源，取 user 域既有主数据，不另造维度）——
  const segQ = useQuery({
    queryKey: ["mkt-segments"],
    queryFn: () => api.listConsumerSegments({ page: 1, size: UNPAGED_SIZE }),
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
      // 发送之后是**发送中**，不是已发送：真实触达由推送通道回执驱动（尚未接入），
      // 收尾要显式点「完成发送」。写成「已发送」会让人以为触达数就在眼前。
      notify.success(r.status === "SCHEDULED"
        ? `已排期 ${fmtTime(r.scheduledAt ?? "")} 发送 · 预计触达 ${r.targetCount} 人`
        : `已下发 · 目标 ${r.targetCount} 人，待通道回执后点「完成发送」落触达数`);
      qc.invalidateQueries({ queryKey: ["mkt"] });
      setSendFor(null);
    },
  });
  /*
   * 收尾：SENDING → SENT。真实触达由推送通道回执驱动（尚未接入），
   * 在那之前由运营显式收尾 —— 让单子停在「发送中」也比假装已送达强：
   * 后者会让「成功 N 人」是编的。目标数取单子上落库的值，成功数由运营填。
   */
  const finishPush = useMutation({
    mutationFn: (v: { no: string; targetCount: number; successCount: number }) =>
      api.finishPushMessage(v.no, { targetCount: v.targetCount, successCount: v.successCount }),
    onSuccess: (r) => {
      notify.success(`已完成 · 目标 ${r.targetCount} 人 / 成功 ${r.successCount} 人`);
      qc.invalidateQueries({ queryKey: ["mkt"] });
    },
  });
  const askFinish = async (p: PushMessage) => {
    const ok = await confirm({
      title: "完成发送",
      desc: `将 ${p.pushNo} 标记为已发送，并落触达统计（目标 ${p.targetCount} 人）。`
        + "成功数以推送通道回执为准；通道未接入时按实际核对结果填。",
      confirmText: "完成",
    });
    // 通道未接入，成功数暂按目标数记；接入后这里改成读回执
    if (ok) finishPush.mutate({ no: p.pushNo, targetCount: p.targetCount, successCount: p.targetCount });
  };

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
  const archiveCouponM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveCoupon(v.no) : api.archiveCoupon(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["mkt"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  // 公告 / 优惠券非主数据，归档确认不要求手输编号（requireText 只留给机柜/站点/角色等）。
  const askArchiveCoupon = async (c: Coupon) => {
    if (await confirm(archiveConfirm("优惠券", c.couponNo))) archiveCouponM.mutate({ no: c.couponNo, undo: false });
  };
  const askUnarchiveCoupon = async (c: Coupon) => {
    if (await confirm(unarchiveConfirm("优惠券", c.couponNo))) archiveCouponM.mutate({ no: c.couponNo, undo: true });
  };

  const q = useQuery<PageResult<Coupon | CouponIssueRecord | Campaign | PushMessage | Referral | AdSlot | AdCampaign | AdDelivery>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["mkt", tab, paging.page, paging.size, keyword, showArchived, campaignStatus, period],
    queryFn: () =>
      tab === "coupons" ? api.listCoupons({ page: paging.page, size: paging.size, keyword, showArchived })
      : tab === "coupon-issues" ? api.listCouponIssueRecords({ page: paging.page, size: paging.size, keyword })
      : tab === "campaigns" ? api.listCampaigns({ page: paging.page, size: paging.size, keyword, status: campaignStatus })
      : tab === "push" ? api.listPushMessages({ page: paging.page, size: paging.size, keyword })
      : tab === "referral" ? api.listReferrals({ page: paging.page, size: paging.size, keyword })
      : tab === "ad-slots" ? api.listAdSlots({ page: paging.page, size: paging.size, keyword })
      : tab === "ad-campaigns" ? api.listAdCampaigns({ page: paging.page, size: paging.size, keyword })
      : api.listAdDeliveries({ page: paging.page, size: paging.size, keyword, period }),
    placeholderData: keepWithinTab(tab),
  });

  // 归档时间列只在「显示已归档」打开时插入：默认视图里它整列都是 "-"，白占宽度。
  // 位置固定在操作列之前，保证操作列仍是最右（§3.2 操作列固定最右）。
  const archivedCol = <T extends { archivedAt: string | null }>(): Column<T>[] =>
    showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];

  const couponAmount = (c: Coupon) => c.type === "CUT" ? money(c.value) : `${c.value} 折`;

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
    { header: "状态", cell: (c) => <StatusBadge map={CAMPAIGN_STATUS} value={c.status} /> },
    { header: "开始", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.startAt)}</span> },
    {
      // 窗口已过要在列上标出来：否则「为什么这条没有启动按钮」得靠人去比日期
      header: "结束",
      cell: (c) => campaignWindowPassed(c)
        ? <Badge tone="muted">窗口已过 {fmtTime(c.endAt)}</Badge>
        : <span className="text-muted-foreground">{fmtTime(c.endAt)}</span>,
    },
    {
      header: t("common.actions"),
      cell: (c) => canEditCampaign ? (
        <div className="flex gap-2">
          {/* 已结束的活动不给编辑：终态还能改规则等于改一份已经生效过的合约 */}
          {c.status !== "ENDED" && <Button size="sm" variant="outline" onClick={() => setCampaignForm(c)}>{t("common.edit")}</Button>}
          {/* 按钮**完全由状态机派生**（campaignActions = CAMPAIGN_TRANSITIONS + 时间窗），
              与 mock 校验同一份口径：终态与窗口已过的行自然没有按钮，不会「亮着点了报错」 */}
          {campaignActions(c).map((a) => (
            <Button
              key={a} size="sm" variant={a === "start" ? "default" : "outline"}
              disabled={transitionCampaign.isPending}
              onClick={() => askTransitionCampaign(c, a)}
            >{CAMPAIGN_TRANSITIONS[a].label}</Button>
          ))}
          {c.status === "ENDED" && <span className="text-muted-foreground">已结束</span>}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const ruleCols: Column<ReferralRule>[] = [
    { header: "规则号", cell: (r) => <span className="txt-strong tabular-nums">{r.ruleNo}</span> },
    { header: "名称", cell: (r) => r.name },
    { header: "奖励对象", cell: (r) => REWARD_TO_LABEL[r.rewardTo] },
    { header: "触发条件", cell: (r) => <span className="text-muted-foreground">{TRIGGER_LABEL[r.trigger]}</span> },
    { header: "单侧金额", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.rewardAmount, r.currency)}</span> },
    { header: "每人上限", className: "text-right", cell: (r) => <span className="tabular-nums">{r.maxPerInviter === 0 ? "不限" : r.maxPerInviter}</span> },
    { header: "生效期", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.startAt)} ~ {fmtTime(r.endAt)}</span> },
    { header: "状态", cell: (r) => <StatusBadge map={RULE_STATUS} value={r.status} /> },
    { header: t("common.actions"), cell: (r) => canEditAd
      ? <Button size="sm" variant="outline" onClick={() => setRuleForm(r)}>{t("common.edit")}</Button>
      : <span className="text-muted-foreground">-</span> },
  ];

  const pushCols: Column<PushMessage>[] = [
    { header: "推送号", cell: (p) => <span className="font-medium">{p.pushNo}</span> },
    { header: "标题", cell: (p) => p.title },
    { header: "渠道", cell: (p) => <Badge tone="outline">{PUSH_CHANNEL_LABEL[p.channel]}</Badge> },
    { header: "目标人群", cell: (p) => <span className="text-muted-foreground">{p.audience}</span> },
    // 目标 / 成功分两列：只看「触达数」看不出失败了多少（关推送权限、停机、黑名单）
    { header: "目标/成功", cell: (p) => <span className="tabular-nums">{p.targetCount}/{p.successCount}</span> },
    { header: "状态", cell: (p) => <StatusBadge map={PUSH_STATUS} value={p.status} /> },
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
          {/* SENDING 必须有出口，否则单子永远卡在「发送中」 */}
          {canPushAction(p.status, "finish") && (
            <Button size="sm" variant="outline" disabled={finishPush.isPending}
              onClick={() => askFinish(p)}>完成发送</Button>
          )}
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
    { header: "状态", cell: (r) => <StatusBadge map={REFERRAL_STATUS} value={r.status} /> },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  const slotCols: Column<AdSlot>[] = [
    { header: "广告位号", cell: (s) => <span className="font-medium">{s.slotNo}</span> },
    { header: "机柜", cell: (s) => <span className="text-muted-foreground">{s.cabinetNo}</span> },
    { header: "位置", cell: (s) => <Badge tone="outline">{s.position === "SCREEN" ? "屏幕" : "机身"}</Badge> },
    { header: "尺寸", cell: (s) => s.size },
    { header: "状态", cell: (s) => <StatusBadge map={AD_SLOT_STATUS} value={s.status} /> },
    { header: "创建时间", cell: (s) => <span className="text-muted-foreground">{fmtTime(s.createdAt)}</span> },
    { header: t("common.actions"), cell: (s) => canEditAd ? <Button size="sm" variant="outline" onClick={() => setSlotForm(s)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const adCampaignCols: Column<AdCampaign>[] = [
    { header: "广告号", cell: (a) => <span className="font-medium">{a.adNo}</span> },
    {
      header: "广告主",
      cell: (a) => (
        <>
          {a.advertiser}
          {/* 名字只作展示，编号才是能连回广告主档案的那个 */}
          <div className="truncate txt-caption text-muted-foreground tabular-nums">{a.advertiserNo}</div>
        </>
      ),
    },
    {
      header: "预算",
      className: "text-right",
      // 后端注释写明这几个字段是「供运营核对」的：没有它时界面上只有曝光与播放数，
      // 核对不了这条广告**被允许花多少钱**、投放有没有超额度。
      cell: (a) => <span className="tabular-nums">{money(a.budget, a.currency)}</span>,
    },
    { header: "创意", cell: (a) => <span className="text-muted-foreground">{a.creative}</span> },
    { header: "定向", cell: (a) => <span className="text-muted-foreground">{a.targeting}</span> },
    { header: "状态", cell: (a) => <StatusBadge map={AD_CAMPAIGN_STATUS} value={a.status} /> },
    { header: "开始", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.startAt)}</span> },
    { header: "结束", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.endAt)}</span> },
    {
      header: t("common.actions"),
      cell: (a) => canEditAd ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <Button size="sm" variant="outline" onClick={() => setAdForm(a)}>{t("common.edit")}</Button>
          {/* 按钮完全由 adCampaignActions（状态机 + 时间窗）派生：终态与窗口已过的行自然无按钮 */}
          {adCampaignActions(a).map((k) => (
            <Button
              key={k} size="sm" variant={k === "launch" ? "default" : "outline"}
              disabled={transitionAd.isPending}
              onClick={() => askTransitionAd(a, k)}
            >{AD_CAMPAIGN_TRANSITIONS[k].label}</Button>
          ))}
          {a.status === "ENDED" && <span className="text-muted-foreground">已下线</span>}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const deliveryCols: Column<AdDelivery>[] = [
    { header: "投放号", cell: (d) => <span className="font-medium">{d.deliveryNo}</span> },
    { header: "广告号", cell: (d) => <span className="text-muted-foreground">{d.adNo}</span> },
    {
      header: "广告位",
      cell: (d) => (
        <>
          <span className="text-muted-foreground tabular-nums">{d.slotNo}</span>
          {/* 同一个位号在不同柜机上是不同的物理屏 —— 「这条广告在哪台机器上播的」
              此前答不出来 */}
          <div className="truncate txt-caption text-muted-foreground tabular-nums">{d.cabinetNo}</div>
        </>
      ),
    },
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
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "coupons" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
        </Toolbar>
      )}
      {tab === "coupon-issues" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索活动号/名称/类型"
          onAdd={canEditCampaign ? () => setCampaignForm({ kind: "满减", startAt: "", endAt: "" }) : undefined}
          addLabel="新增活动"
          onExport={onExportOf<Campaign>("活动", [
            { header: "活动号", value: (c) => c.campaignNo },
            { header: "名称", value: (c) => c.name },
            { header: "类型", value: (c) => c.kind },
            { header: "规则", value: (c) => c.rule },
            { header: "状态", value: (c) => CAMPAIGN_STATUS[c.status].label },
            { header: "开始", value: (c) => fmtTime(c.startAt) },
            { header: "结束", value: (c) => fmtTime(c.endAt) },
          ])}
        >
          <FilterSelect value={campaignStatus} onChange={(v) => { setCampaignStatus(v); paging.reset(); }} allLabel="全部状态" options={CAMPAIGN_STATUS} />
        </Toolbar>
      )}
      {tab === "push" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索邀请号/邀请人/受邀人"
          onExport={onExportOf<Referral>("邀请裂变", [
            { header: "邀请号", value: (r) => r.inviteNo },
            { header: "邀请人", value: (r) => r.inviter },
            { header: "受邀人", value: (r) => r.invitee },
            { header: "奖励", value: (r) => money(r.reward, r.currency) },
            { header: "状态", value: (r) => (r.status === "REWARDED" ? "已发奖" : "待发奖") },
            { header: "时间", value: (r) => fmtTime(r.createdAt) },
          ])}
        >
          {/* 子视图：邀请记录是只读流水（C 端自动产生），奖励规则才是可配置项。
              不拆成两个 tab —— 它们是同一件事的「结果」与「口径」，放一起才好对照 */}
          <div className={segmentedTrackClass()} role="group" aria-label="裂变子视图">
            {([["records", "邀请记录"], ["rules", "奖励规则"]] as const).map(([k, label]) => (
              <button
                key={k} type="button" aria-pressed={refView === k}
                onClick={() => { setRefView(k); paging.reset(); }}
                className={segmentedItemClass(refView === k, "px-2.5 py-1 text-sm")}
              >{label}</button>
            ))}
          </div>
        </Toolbar>
      )}
      {tab === "ad-slots" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder="搜索投放号/广告号/广告位"
          onExport={onExportOf<AdDelivery>(`投放与曝光-${periodLabel(period)}`, [
            { header: "投放号", value: (d) => d.deliveryNo },
            { header: "广告号", value: (d) => d.adNo },
            { header: "广告位", value: (d) => d.slotNo },
            { header: "曝光", value: (d) => Math.round(d.impressions) },
            { header: "播放", value: (d) => Math.round(d.plays) },
            { header: "日期", value: (d) => d.date },
          ])}
        >
          {/* 曝光是按天回传的事实行，周期筛选就是它的「完成态」——
              动作（上线/暂停/下线）挂在广告活动上，不挂事实表 */}
          <FilterSelect
            value={period}
            onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }}
            options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
            aria-label="按统计周期筛选"
          />
        </Toolbar>
      )}
      {tab === "coupons" && <DataTable rowKey={(c: Coupon) => c.couponNo} columns={couponCols} rows={q.data?.list as Coupon[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的优惠券——换个关键词，或点「新增优惠券」建一张。" : "暂无在用优惠券——可能都已归档（打开「显示已归档」查看），或点「新增优惠券」建第一张。"} />}
      {tab === "coupon-issues" && <DataTable rowKey={(r: CouponIssueRecord) => r.issueNo} columns={issueCols} rows={q.data?.list as CouponIssueRecord[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无发放记录——到「优惠券」tab 选一张在用的券点「发放」，这里会逐笔留痕。" />}
      {tab === "campaigns" && (
        <InfoNotice>
          启停走状态机：草稿 / 已暂停 →启动→ 进行中 →暂停→ 已暂停；进行中 / 已暂停 →结束→ 已结束（终态，不可复活）。
          结束时间已过的活动不能启动——请先在编辑里延长结束时间。
        </InfoNotice>
      )}
      {tab === "campaigns" && <DataTable rowKey={(c: Campaign) => c.campaignNo} columns={campaignCols} rows={q.data?.list as Campaign[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty={campaignStatus ? `没有「${CAMPAIGN_STATUS[campaignStatus as Campaign["status"]].label}」的活动——换个状态看看。` : "暂无营销活动——点「新增活动」配置满减 / 拉新 / 签到规则。"} />}
      {tab === "push" && <DataTable rowKey={(p: PushMessage) => p.pushNo} columns={pushCols} rows={q.data?.list as PushMessage[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无推送任务——点「新增推送」创建一条 App 推送或订阅消息。" />}
      {tab === "referral" && refView === "records" && <DataTable rowKey={(r: Referral) => r.inviteNo} columns={referralCols} rows={q.data?.list as Referral[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无邀请记录——用户在 C 端发起邀请后自动生成，无需在此手工录入。" />}
      {tab === "referral" && refView === "rules" && (
        <>
          {canEditAd
            ? <div className="mb-3 flex justify-end"><Button size="sm" onClick={() => setRuleForm({ rewardTo: "BOTH", trigger: "FIRST_ORDER", currency: "AED", maxPerInviter: 10, status: "ACTIVE" })}>新增规则</Button></div>
            : <ReadOnlyNotice what="奖励规则配置" perm="marketing:ad:manage" />}
          <DataTable rowKey={(r: ReferralRule) => r.ruleNo} columns={ruleCols} rows={rules.data?.list} loading={rules.isLoading} error={rules.error} onRetry={rules.refetch}
            empty="还没有奖励规则——没有生效规则时 C 端邀请不发奖，点「新增规则」配置奖多少、奖给谁、什么条件触发。" />
        </>
      )}
      {tab === "ad-slots" && <DataTable rowKey={(s: AdSlot) => s.slotNo} columns={slotCols} rows={q.data?.list as AdSlot[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无广告位——点「新增广告位」把机柜屏幕 / 机身登记为可售位。" />}
      {tab === "ad-campaigns" && <DataTable rowKey={(a: AdCampaign) => a.adNo} columns={adCampaignCols} rows={q.data?.list as AdCampaign[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty="暂无广告活动——点「新增广告活动」录入广告主与创意后再排期投放。" />}
      {tab === "ad-delivery" && <DataTable rowKey={(d: AdDelivery) => d.deliveryNo} columns={deliveryCols} rows={q.data?.list as AdDelivery[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} empty={`${periodLabel(period)}内没有投放数据——曝光按天回传，需广告先上线；可换更长的周期再看。`} />}
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      <FormDrawer
        open={!!ruleForm}
        onOpenChange={(o) => !o && setRuleForm(null)}
        titleNew="新增奖励规则"
        titleEdit={`编辑规则 ${ruleForm?.ruleNo ?? ""}`}
        isEdit={!!ruleForm?.ruleNo}
        fields={REFERRAL_RULE_FIELDS}
        value={(ruleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRuleForm(v as Partial<ReferralRule>)}
        onSubmit={() => ruleForm && saveRule.mutate(ruleForm)}
        submitting={saveRule.isPending}
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
              <div className="rounded-card bg-muted px-3.5 py-2 text-sm text-muted-foreground">
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
            <Field label="当前状态"><StatusBadge map={PUSH_STATUS} value={sendFor.status} /></Field>
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
            <div className="rounded-card bg-muted px-3.5 py-2 text-sm text-muted-foreground">
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
