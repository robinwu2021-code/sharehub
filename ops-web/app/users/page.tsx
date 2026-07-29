"use client";

import { Suspense, useEffect, useState, type ReactNode } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Input, Select } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Progress } from "@/components/ui/progress";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type {
  CUser, Member, Wallet, UserRisk, UserBlacklist,
  FreeUserWhitelist, RechargePackage, WhitelistReason,
} from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "list", label: "用户", phase: 2 as const },
  { key: "risk", label: "风控用户", phase: 2 as const },
  { key: "blacklist", label: "黑名单", phase: 2 as const },
  { key: "whitelist", label: "免费用户白名单", phase: 2 as const },
  { key: "members", label: "会员/次卡", phase: 3 as const },
  { key: "wallets", label: "钱包", phase: 3 as const },
  { key: "recharge", label: "充值套餐", phase: 3 as const },
];

// —— 免费用户白名单（规格 §7）——
// 竞品「免费用户」挂在订单域且用途是自由文本；我们归用户域（它本质是用户属性），
// 并把用途做成枚举——免费额度是真金白银，事后要能按用途归集成本。
const REASON_OPTIONS = [
  { value: "INTERNAL_TEST", label: "内测" },
  { value: "VIP", label: "VIP" },
  { value: "BD_DEMO", label: "BD 演示" },
  { value: "MERCHANT_SELF", label: "商户自用" },
];
const REASON_LABEL: Record<WhitelistReason, string> = {
  INTERNAL_TEST: "内测", VIP: "VIP", BD_DEMO: "BD 演示", MERCHANT_SELF: "商户自用",
};
const QUOTA_LABEL: Record<FreeUserWhitelist["quotaType"], string> = {
  UNLIMITED: "不限额", TIMES: "限次数", AMOUNT: "限金额",
};
const WL_STATUS: Record<FreeUserWhitelist["status"], { label: string; tone: "success" | "muted" | "danger" }> = {
  ACTIVE: { label: "生效中", tone: "success" },
  EXPIRED: { label: "已过期", tone: "muted" },
  REVOKED: { label: "已撤销", tone: "danger" },
};

const WHITELIST_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, required: true, section: "用户", placeholder: "U3001" },
  { key: "nickname", label: "昵称", required: true, maxLength: 30, section: "用户", placeholder: "用户昵称" },
  { key: "phone", label: "手机", section: "用户", placeholder: "+971501234567", pattern: { re: "^\\+?[0-9]{7,15}$", msg: "手机号格式不正确（示例 +971501234567）" } },
  { key: "reason", label: "用途", type: "select", required: true, section: "额度", options: REASON_OPTIONS, help: "必填且为枚举：事后要按用途归集免费成本，自由文本归集不了" },
  { key: "quotaType", label: "额度类型", type: "select", required: true, section: "额度", options: [
    { value: "UNLIMITED", label: "不限额" }, { value: "TIMES", label: "限次数" }, { value: "AMOUNT", label: "限金额" },
  ] },
  // 联动禁用：不限额时额度输入无意义，FormDrawer 会自动清空其值
  { key: "quotaValue", label: "额度值（次数 / AED）", type: "number", required: true, min: 1, section: "额度",
    disabledWhen: (v) => v.quotaType === "UNLIMITED", help: "额度类型为「不限额」时无需填写" },
  { key: "usedValue", label: "已用额度", type: "number", min: 0, section: "额度",
    disabledWhen: (v) => v.quotaType === "UNLIMITED", help: "由订单核销回写，此处仅供纠偏" },
  { key: "validFrom", label: "生效日期", type: "date", required: true, section: "有效期与审计" },
  { key: "validTo", label: "失效日期", type: "date", required: true, section: "有效期与审计" },
  { key: "grantedBy", label: "授予人", required: true, maxLength: 30, section: "有效期与审计", help: "审计用：免费额度必须有人负责" },
  { key: "status", label: "状态", type: "select", required: true, section: "有效期与审计", options: [
    { value: "ACTIVE", label: "生效中" }, { value: "EXPIRED", label: "已过期" }, { value: "REVOKED", label: "已撤销" },
  ] },
];

// —— 充值套餐（规格 §8）：比竞品多「赠额有效期」与「适用市场」——
const MARKET_OPTIONS = [
  { value: "AE", label: "阿联酋 AE" },
  { value: "SA", label: "沙特 SA" },
  { value: "KW", label: "科威特 KW" },
  { value: "QA", label: "卡塔尔 QA" },
  { value: "BH", label: "巴林 BH" },
  { value: "OM", label: "阿曼 OM" },
  { value: "EG", label: "埃及 EG" },
];
const RECHARGE_FIELDS: FieldDef[] = [
  { key: "packageNo", label: "套餐号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "基本信息" },
  { key: "name", label: "套餐名称", required: true, maxLength: 20, section: "基本信息", placeholder: "常用包" },
  { key: "payAmount", label: "充值金额", type: "number", required: true, min: 1, section: "金额" },
  { key: "giftAmount", label: "赠送金额", type: "number", required: true, min: 0, section: "金额", help: "填 0 表示无赠送" },
  { key: "currency", label: "币种", type: "select", required: true, section: "金额", options: [
    { value: "AED", label: "AED" }, { value: "SAR", label: "SAR" }, { value: "USD", label: "USD" },
  ] },
  // multiselect + csv：值以 "AE,SA" 形式存储，与后端多国家字段一致
  { key: "markets", label: "适用市场", type: "multiselect", csv: true, required: true, section: "投放", options: MARKET_OPTIONS,
    placeholder: "选择适用国家", help: "只在所选市场的 C 端展示；竞品无此维度" },
  { key: "validDays", label: "赠额有效期（天）", type: "number", required: true, min: 1, max: 3650, section: "投放", help: "赠送金额的过期天数，充值本金不过期" },
  { key: "sortNo", label: "排序", type: "number", required: true, min: 1, section: "投放", help: "数字小的排前面" },
  { key: "status", label: "状态", type: "select", required: true, section: "投放", options: [
    { value: "ENABLED", label: "上架" }, { value: "DISABLED", label: "下架" },
  ] },
];

const MEMBER_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, placeholder: "U0001" },
  { key: "nickname", label: "昵称", placeholder: "会员昵称" },
  { key: "level", label: "等级", type: "select", options: [
    { value: "SILVER", label: "白银" },
    { value: "GOLD", label: "黄金" },
    { value: "PLATINUM", label: "铂金" },
  ] },
  { key: "points", label: "积分", type: "number" },
  { key: "cardType", label: "次卡类型", placeholder: "月卡 / 季卡 / 无" },
  { key: "expireAt", label: "到期时间", placeholder: "2026-12-31T00:00:00Z" },
];

const WALLET_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, placeholder: "U0001" },
  { key: "nickname", label: "昵称", placeholder: "用户昵称" },
  { key: "balance", label: "余额", type: "number" },
  { key: "bonus", label: "赠额", type: "number" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

function UsersInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "list");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [memberForm, setMemberForm] = useState<Partial<Member> | null>(null);
  const [walletForm, setWalletForm] = useState<Partial<Wallet> | null>(null);
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const canEditMember = allow("user:member:update");
  const canEditWallet = allow("user:wallet:update");
  const canEditWhitelist = allow("user:risk:update");
  const canEditPackage = allow("user:wallet:update");
  const { confirm, dialog } = useConfirm();

  const users = useQuery({
    queryKey: ["users", page, keyword],
    queryFn: () => api.listUsers({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  const members = useQuery({
    queryKey: ["members", page, keyword],
    queryFn: () => api.listMembers({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "members",
  });

  const wallets = useQuery({
    queryKey: ["wallets", page, keyword],
    queryFn: () => api.listWallets({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "wallets",
  });
  const risks = useQuery({
    queryKey: ["user-risks", page, keyword],
    queryFn: () => api.listUserRisks({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "risk",
  });
  const blacklisted = useQuery({
    queryKey: ["user-blacklist", page, keyword],
    queryFn: () => api.listUserBlacklist({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "blacklist",
  });

  // —— 免费用户白名单（B4）——
  const [wlReason, setWlReason] = useState("");
  const [wlStatus, setWlStatus] = useState("");
  const [wlForm, setWlForm] = useState<Partial<FreeUserWhitelist> | null>(null);
  const whitelist = useQuery({
    queryKey: ["free-whitelist", page, keyword, wlReason, wlStatus],
    queryFn: () => api.listFreeWhitelist({ page, size: SIZE, keyword, reason: wlReason || undefined, status: wlStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "whitelist",
  });
  const saveWl = useMutation({
    mutationFn: (v: Partial<FreeUserWhitelist>) => api.saveFreeWhitelist(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["free-whitelist"] }); notify.success(t("common.success")); setWlForm(null); },
  });
  const revokeWl = useMutation({
    mutationFn: (userNo: string) => api.revokeFreeWhitelist(userNo),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["free-whitelist"] }); notify.success("已撤销白名单"); },
  });
  // FieldDef 管不了跨字段约束（有效期先后），故提交前补校验
  const submitWl = () => {
    if (!wlForm) return;
    if (wlForm.validFrom && wlForm.validTo && wlForm.validTo < wlForm.validFrom) { notify.error("失效日期不得早于生效日期"); return; }
    if (wlForm.quotaType !== "UNLIMITED" && (wlForm.usedValue ?? 0) > (wlForm.quotaValue ?? 0)) { notify.error("已用额度不得超过额度值"); return; }
    saveWl.mutate(wlForm);
  };
  const askRevokeWl = async (w: FreeUserWhitelist) => {
    const ok = await confirm({
      title: `撤销白名单 ${w.userNo}`,
      desc: `${w.nickname} · ${REASON_LABEL[w.reason]}。撤销后该用户立即恢复正常计费，历史免费订单不受影响。`,
      danger: true,
      confirmText: "确认撤销",
    });
    if (ok) revokeWl.mutate(w.userNo);
  };

  // —— 充值套餐（B5）——
  const [pkgStatus, setPkgStatus] = useState("");
  const [pkgForm, setPkgForm] = useState<Partial<RechargePackage> | null>(null);
  const packages = useQuery({
    queryKey: ["recharge-packages", page, keyword, pkgStatus],
    queryFn: () => api.listRechargePackages({ page, size: SIZE, keyword, status: pkgStatus || undefined }),
    placeholderData: keepPreviousData,
    enabled: tab === "recharge",
  });
  const savePkg = useMutation({
    mutationFn: (v: Partial<RechargePackage>) => api.saveRechargePackage(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recharge-packages"] }); notify.success(t("common.success")); setPkgForm(null); },
  });

  const bl = useMutation({
    mutationFn: (v: { no: string; blacklisted: boolean }) => api.setBlacklist(v.no, v.blacklisted),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });

  const saveMember = useMutation({
    mutationFn: (v: Partial<Member>) => api.saveMember(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["members"] }); notify.success(t("common.success")); setMemberForm(null); },
  });

  const saveWallet = useMutation({
    mutationFn: (v: Partial<Wallet>) => api.saveWallet(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["wallets"] }); notify.success(t("common.success")); setWalletForm(null); },
  });

  const userCols: Column<CUser>[] = [
    { header: "用户号", cell: (u) => <span className="font-medium">{u.cUserNo}</span> },
    { header: "昵称", cell: (u) => u.nickname },
    { header: "手机", cell: (u) => <span className="text-muted-foreground">{u.phone}</span> },
    { header: "信用分", cell: (u) => <span className="tabular-nums">{u.creditScore}</span> },
    { header: "订单数", cell: (u) => <span className="tabular-nums">{u.orders}</span> },
    { header: "注册", cell: (u) => <span className="text-muted-foreground">{fmtTime(u.registeredAt)}</span> },
    { header: "状态", cell: (u) => u.blacklisted ? <Badge tone="danger">黑名单</Badge> : <Badge tone="success">正常</Badge> },
    {
      header: "操作",
      cell: (u) => allow("user:risk:update") ? (
        <Button size="sm" variant="outline" disabled={bl.isPending} onClick={() => bl.mutate({ no: u.cUserNo, blacklisted: !u.blacklisted })}>
          {u.blacklisted ? "解除拉黑" : "拉黑"}
        </Button>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const LEVEL: Record<Member["level"], { label: string; tone: "muted" | "warning" | "success" }> = {
    SILVER: { label: "白银", tone: "muted" },
    GOLD: { label: "黄金", tone: "warning" },
    PLATINUM: { label: "铂金", tone: "success" },
  };
  const memberCols: Column<Member>[] = [
    { header: "用户号", cell: (m) => <span className="font-medium">{m.userNo}</span> },
    { header: "昵称", cell: (m) => m.nickname },
    { header: "等级", cell: (m) => <Badge tone={LEVEL[m.level].tone}>{LEVEL[m.level].label}</Badge> },
    { header: "积分", cell: (m) => <span className="tabular-nums">{Math.round(m.points)}</span> },
    { header: "次卡", cell: (m) => m.cardType },
    { header: "到期", cell: (m) => <span className="text-muted-foreground">{fmtTime(m.expireAt)}</span> },
    { header: t("common.actions"), cell: (m) => canEditMember ? <Button size="sm" variant="outline" onClick={() => setMemberForm(m)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const RISK_LEVEL: Record<UserRisk["riskLevel"], { label: string; tone: "danger" | "warning" | "muted" }> = {
    HIGH: { label: "高危", tone: "danger" },
    MEDIUM: { label: "中等", tone: "warning" },
    LOW: { label: "低风险", tone: "muted" },
  };
  const riskCols: Column<UserRisk>[] = [
    { header: "风控号", cell: (r) => <span className="font-medium">{r.riskNo}</span> },
    { header: "用户号", cell: (r) => r.userNo },
    { header: "昵称", cell: (r) => r.nickname },
    { header: "手机", cell: (r) => <span className="text-muted-foreground">{r.phone}</span> },
    { header: "信用分", cell: (r) => <span className="tabular-nums">{r.creditScore}</span> },
    { header: "风险等级", cell: (r) => <Badge tone={RISK_LEVEL[r.riskLevel].tone}>{RISK_LEVEL[r.riskLevel].label}</Badge> },
    { header: "原因", cell: (r) => <span className="text-muted-foreground">{r.reason}</span> },
    { header: "标记时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.flaggedAt)}</span> },
  ];
  const blacklistCols: Column<UserBlacklist>[] = [
    { header: "黑名单号", cell: (b) => <span className="font-medium">{b.blacklistNo}</span> },
    { header: "用户号", cell: (b) => b.userNo },
    { header: "昵称", cell: (b) => b.nickname },
    { header: "手机", cell: (b) => <span className="text-muted-foreground">{b.phone}</span> },
    { header: "原因", cell: (b) => b.reason },
    { header: "拉黑时间", cell: (b) => <span className="text-muted-foreground">{fmtTime(b.blacklistedAt)}</span> },
    { header: "状态", cell: (b) => b.status === "ACTIVE" ? <Badge tone="danger">拉黑中</Badge> : <Badge tone="muted">已解除</Badge> },
    {
      header: "操作",
      cell: (b) => allow("user:risk:update") && b.status === "ACTIVE"
        ? <Button size="sm" variant="outline" disabled={bl.isPending} onClick={() => bl.mutate({ no: b.userNo, blacklisted: false })}>解除</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];
  const walletCols: Column<Wallet>[] = [
    { header: "用户号", cell: (w) => <span className="font-medium">{w.userNo}</span> },
    { header: "昵称", cell: (w) => w.nickname },
    { header: "余额", cell: (w) => <span className="tabular-nums">{money(w.balance, w.currency)}</span> },
    { header: "赠额", cell: (w) => <span className="tabular-nums">{money(w.bonus, w.currency)}</span> },
    { header: "币种", cell: (w) => <Badge tone="outline">{w.currency}</Badge> },
    // 用户价值画像四列：钱包页即可判断该用户值不值得挽留/补偿，不必再跳订单页
    { header: "订单数", cell: (w) => <span className="tabular-nums">{w.orderCount}</span> },
    { header: "订单金额", cell: (w) => <span className="tabular-nums">{money(w.orderAmount, w.currency)}</span> },
    { header: "充值次数", cell: (w) => <span className="tabular-nums">{w.rechargeCount}</span> },
    { header: "充值金额", cell: (w) => <span className="tabular-nums">{money(w.rechargeAmount, w.currency)}</span> },
    { header: "更新时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.updatedAt)}</span> },
    { header: t("common.actions"), cell: (w) => canEditWallet ? <Button size="sm" variant="outline" onClick={() => setWalletForm(w)}>调整余额</Button> : <span className="text-muted-foreground">-</span> },
  ];

  // 过期/撤销整行灰显（B0 补丁 rowClassName）；dim 保留给需要额外弱化的单元格
  const dim = (w: FreeUserWhitelist, node: ReactNode) =>
    w.status === "ACTIVE" ? node : <span className="text-muted-foreground">{node}</span>;
  const whitelistCols: Column<FreeUserWhitelist>[] = [
    { header: "用户号", cell: (w) => dim(w, <span className="font-medium tabular-nums">{w.userNo}</span>) },
    { header: "昵称", cell: (w) => dim(w, w.nickname) },
    { header: "手机", cell: (w) => <span className="text-muted-foreground">{w.phone}</span> },
    { header: "用途", cell: (w) => <Badge tone="outline">{REASON_LABEL[w.reason]}</Badge> },
    { header: "额度类型", cell: (w) => dim(w, QUOTA_LABEL[w.quotaType]) },
    {
      header: "已用/总额",
      cell: (w) => w.quotaType === "UNLIMITED"
        ? <span className="text-muted-foreground">不限</span>
        : <Progress value={w.usedValue} total={w.quotaValue} warnAt={90} />,
    },
    { header: "有效期", cell: (w) => <span className="text-muted-foreground">{w.validFrom} ~ {w.validTo}</span> },
    { header: "授予人", cell: (w) => <span className="text-muted-foreground">{w.grantedBy}</span> },
    { header: "状态", cell: (w) => <Badge tone={WL_STATUS[w.status].tone}>{WL_STATUS[w.status].label}</Badge> },
    {
      header: t("common.actions"),
      cell: (w) => canEditWhitelist ? (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setWlForm(w)}>{t("common.edit")}</Button>
          {w.status === "ACTIVE" && (
            <Button size="sm" variant="outline" disabled={revokeWl.isPending} onClick={() => askRevokeWl(w)}>撤销</Button>
          )}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const packageCols: Column<RechargePackage>[] = [
    { header: "套餐号", cell: (r) => <span className="font-medium tabular-nums">{r.packageNo}</span> },
    { header: "名称", cell: (r) => r.status === "ENABLED" ? r.name : <span className="text-muted-foreground">{r.name}</span> },
    // 「充 X 送 Y」合并一列：运营看的是这组关系，拆两列反而要心算
    {
      header: "充值方案",
      cell: (r) => (
        <span className="tabular-nums">
          充 {money(r.payAmount, r.currency)}
          {r.giftAmount > 0
            ? <> 送 <span className="text-[var(--success)]">{money(r.giftAmount, r.currency)}</span></>
            : <span className="text-muted-foreground"> · 无赠送</span>}
        </span>
      ),
    },
    { header: "到账合计", cell: (r) => <span className="tabular-nums">{money(r.payAmount + r.giftAmount, r.currency)}</span> },
    { header: "适用市场", cell: (r) => <div className="flex flex-wrap gap-1">{r.markets.split(",").filter(Boolean).map((m) => <Badge key={m} tone="outline">{m}</Badge>)}</div> },
    { header: "赠额有效期", cell: (r) => <span className="tabular-nums">{r.validDays} 天</span> },
    { header: "排序", cell: (r) => <span className="tabular-nums">{r.sortNo}</span> },
    { header: "状态", cell: (r) => <Badge tone={r.status === "ENABLED" ? "success" : "muted"}>{r.status === "ENABLED" ? "上架" : "下架"}</Badge> },
    { header: t("common.actions"), cell: (r) => canEditPackage ? <Button size="sm" variant="outline" onClick={() => setPkgForm(r)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const active = tab === "list" ? users
    : tab === "members" ? members
    : tab === "wallets" ? wallets
    : tab === "risk" ? risks
    : tab === "whitelist" ? whitelist
    : tab === "recharge" ? packages
    : blacklisted;

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      {tab === "list" && (
        <>
          <div className="mb-4"><Input className="w-64" placeholder="搜索昵称 / 手机 / 用户号" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} /></div>
          <DataTable rowKey={(u: CUser) => u.cUserNo} columns={userCols} rows={users.data?.list} loading={users.isLoading} />
        </>
      )}
      {tab === "members" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索昵称 / 用户号"
            onAdd={canEditMember ? () => setMemberForm({ level: "SILVER", points: 0, cardType: "无", nickname: "" }) : undefined}
            addLabel="新增会员"
          />
          <DataTable rowKey={(m: Member) => m.userNo} columns={memberCols} rows={members.data?.list} loading={members.isLoading} />
        </>
      )}
      {tab === "risk" && (
        <>
          <div className="mb-4"><Input className="w-64" placeholder="搜索用户号 / 昵称 / 手机" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} /></div>
          <DataTable rowKey={(r: UserRisk) => r.riskNo} columns={riskCols} rows={risks.data?.list} loading={risks.isLoading} />
        </>
      )}
      {tab === "blacklist" && (
        <>
          <div className="mb-4"><Input className="w-64" placeholder="搜索用户号 / 昵称" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} /></div>
          <DataTable rowKey={(b: UserBlacklist) => b.blacklistNo} columns={blacklistCols} rows={blacklisted.data?.list} loading={blacklisted.isLoading} />
        </>
      )}
      {tab === "whitelist" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索用户号 / 昵称 / 手机 / 授予人"
            onAdd={canEditWhitelist ? () => setWlForm({ reason: "INTERNAL_TEST", quotaType: "TIMES", quotaValue: 10, usedValue: 0, status: "ACTIVE", nickname: "", phone: "", validFrom: "", validTo: "", grantedBy: "" }) : undefined}
            addLabel="新增白名单"
            onExport={() => exportCsv<FreeUserWhitelist>("免费用户白名单", [
              { header: "用户号", value: (w) => w.userNo },
              { header: "昵称", value: (w) => w.nickname },
              { header: "手机", value: (w) => w.phone },
              { header: "用途", value: (w) => REASON_LABEL[w.reason] },
              { header: "额度类型", value: (w) => QUOTA_LABEL[w.quotaType] },
              { header: "额度值", value: (w) => (w.quotaType === "UNLIMITED" ? "不限" : w.quotaValue) },
              { header: "已用", value: (w) => (w.quotaType === "UNLIMITED" ? "-" : w.usedValue) },
              { header: "生效日期", value: (w) => w.validFrom },
              { header: "失效日期", value: (w) => w.validTo },
              { header: "授予人", value: (w) => w.grantedBy },
              { header: "状态", value: (w) => WL_STATUS[w.status].label },
            ], whitelist.data?.list ?? [])}
          >
            <Select value={wlReason} onChange={(e) => { setWlReason(e.target.value); setPage(1); }}>
              <option value="">全部用途</option>
              {REASON_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </Select>
            <Select value={wlStatus} onChange={(e) => { setWlStatus(e.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="ACTIVE">生效中</option>
              <option value="EXPIRED">已过期</option>
              <option value="REVOKED">已撤销</option>
            </Select>
          </Toolbar>
          {!canEditWhitelist && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无白名单维护权限（user:risk:update）</div>}
          <DataTable
            rowKey={(w: FreeUserWhitelist) => w.userNo}
            columns={whitelistCols}
            rows={whitelist.data?.list}
            loading={whitelist.isLoading}
            rowClassName={(w) => (w.status === "ACTIVE" ? undefined : "opacity-60")}
            empty="暂无免费用户——内测/VIP/BD 演示/商户自用需要免单时在此登记，登记后订单会落到「订单 · 免费订单」"
          />
        </>
      )}
      {tab === "recharge" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索套餐号 / 名称 / 适用市场"
            onAdd={canEditPackage ? () => setPkgForm({ name: "", payAmount: 50, giftAmount: 5, currency: "AED", markets: "AE", validDays: 180, sortNo: 1, status: "ENABLED" }) : undefined}
            addLabel="新增套餐"
            onExport={() => exportCsv<RechargePackage>("充值套餐", [
              { header: "套餐号", value: (r) => r.packageNo },
              { header: "名称", value: (r) => r.name },
              { header: "充值金额", value: (r) => r.payAmount },
              { header: "赠送金额", value: (r) => r.giftAmount },
              { header: "币种", value: (r) => r.currency },
              { header: "适用市场", value: (r) => r.markets },
              { header: "赠额有效期(天)", value: (r) => r.validDays },
              { header: "排序", value: (r) => r.sortNo },
              { header: "状态", value: (r) => (r.status === "ENABLED" ? "上架" : "下架") },
            ], packages.data?.list ?? [])}
          >
            <Select value={pkgStatus} onChange={(e) => { setPkgStatus(e.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="ENABLED">上架</option>
              <option value="DISABLED">下架</option>
            </Select>
          </Toolbar>
          {!canEditPackage && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无充值套餐维护权限（user:wallet:update）</div>}
          <DataTable
            rowKey={(r: RechargePackage) => r.packageNo}
            columns={packageCols}
            rowClassName={(p: RechargePackage) => (p.status === "ENABLED" ? undefined : "opacity-60")}
            rows={packages.data?.list}
            loading={packages.isLoading}
            empty="暂无充值套餐——先配置「充 X 送 Y」套餐，C 端钱包页才有充值选项"
          />
        </>
      )}
      {tab === "wallets" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索昵称 / 用户号"
          />
          <DataTable rowKey={(w: Wallet) => w.userNo} columns={walletCols} rows={wallets.data?.list} loading={wallets.isLoading} />
        </>
      )}
      {active.data && <Pagination page={page} size={SIZE} total={active.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!memberForm}
        onOpenChange={(o) => !o && setMemberForm(null)}
        titleNew="新增会员"
        titleEdit={`编辑会员 ${memberForm?.userNo ?? ""}`}
        isEdit={!!memberForm?.userNo}
        fields={MEMBER_FIELDS}
        value={(memberForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setMemberForm(v as Partial<Member>)}
        onSubmit={() => memberForm && saveMember.mutate(memberForm)}
        submitting={saveMember.isPending}
      />

      <FormDrawer
        open={!!walletForm}
        onOpenChange={(o) => !o && setWalletForm(null)}
        titleNew="新增钱包"
        titleEdit={`调整钱包 ${walletForm?.userNo ?? ""}`}
        isEdit={!!walletForm?.userNo}
        fields={WALLET_FIELDS}
        value={(walletForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setWalletForm(v as Partial<Wallet>)}
        onSubmit={() => walletForm && saveWallet.mutate(walletForm)}
        submitting={saveWallet.isPending}
      />

      <FormDrawer
        open={!!wlForm}
        onOpenChange={(o) => !o && setWlForm(null)}
        titleNew="新增免费用户白名单"
        titleEdit={`编辑白名单 ${wlForm?.userNo ?? ""}`}
        isEdit={!!wlForm?.userNo}
        fields={WHITELIST_FIELDS}
        value={(wlForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setWlForm(v as Partial<FreeUserWhitelist>)}
        onSubmit={submitWl}
        submitting={saveWl.isPending}
      />

      <FormDrawer
        open={!!pkgForm}
        onOpenChange={(o) => !o && setPkgForm(null)}
        titleNew="新增充值套餐"
        titleEdit={`编辑套餐 ${pkgForm?.packageNo ?? ""}`}
        isEdit={!!pkgForm?.packageNo}
        fields={RECHARGE_FIELDS}
        value={(pkgForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPkgForm(v as Partial<RechargePackage>)}
        onSubmit={() => pkgForm && savePkg.mutate(pkgForm)}
        submitting={savePkg.isPending}
      />

      {dialog}
    </div>
  );
}

export default function UsersPage() {
  return <Suspense fallback={null}><UsersInner /></Suspense>;
}
