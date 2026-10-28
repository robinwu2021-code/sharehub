"use client";

import { Suspense, useEffect, useState, type ReactNode } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { UNPAGED_SIZE, RECENT_LIMIT } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Input, Select } from "@/components/ui/input";
import { Drawer, Field } from "@/components/ui/drawer";
import { Timeline } from "@/components/ui/timeline";
import { AvatarLabel } from "@/components/ui/avatar";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { OrderStatusBadge } from "@/components/status";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Progress } from "@/components/ui/progress";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
// 等级名与次卡状态：用户详情抽屉与「销售管理 › 会员」共用一份
import { LEVEL, CARD_STATUS } from "@/components/user/membership";
import { RechargeOrdersPanel } from "@/components/user/recharge-orders";
import {
  CREDIT_SCORE_MIN, CREDIT_SCORE_MAX, RISK_MEDIUM_BELOW, riskLevelOf,
  MEMBER_CARD_LABEL, MEMBER_CARD_NONE, PROFILE_RECENT_TXNS,
} from "@/lib/types";
import type {
  CUser, Wallet, WalletTxn, UserRisk, UserBlacklist,
  FreeUserWhitelist, RechargePackage, WhitelistReason,
  MemberCard, RentOrder, UserProfile,
  LogoffItem, CUserInvoiceRow,
} from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 「用户」在菜单里叫「用户列表」——以菜单为准。
// 2026-10-28「会员套餐」「会员 / 次卡」搬到「销售管理 › 会员」（components/user/membership.tsx），本页不再留第二份。
// 2026-10-28 按运营顺序（OM05）：用户 → 风险 → 资产，与菜单一致。充值订单自财务管理搬来，与套餐同在 recharge 页签。
// 开票申请（invoices）属发票模块（OM09），该模块调整时迁走。
const TAB_KEYS = ["list", "logoffs", "risk", "blacklist", "whitelist", "wallets", "recharge", "invoices"] as const;

// —— 账号 / 风控 / 黑名单三张表的状态映射 ——
// 拉黑与否不是后端枚举，但同一对徽标在列表页和详情抽屉各出现一次，
// 收成映射表后两处不可能配出不同颜色。
const ACCOUNT_STATUS: StatusMap<"NORMAL" | "BLACKLISTED"> = {
  NORMAL: { label: "正常", tone: "success" },
  BLACKLISTED: { label: "黑名单", tone: "danger" },
};
const RISK_LEVEL: StatusMap<UserRisk["riskLevel"]> = {
  HIGH: { label: "高危", tone: "danger" },
  MEDIUM: { label: "中等", tone: "warning" },
  LOW: { label: "低风险", tone: "muted" },
};
const BL_STATUS: StatusMap<UserBlacklist["status"]> = {
  ACTIVE: { label: "拉黑中", tone: "danger" },
  RELEASED: { label: "已解除", tone: "muted" },
};

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
// 状态映射表留在页面（哪个状态叫什么、该是什么色是本页业务语义），
// 但**只留这一份**：徽标走 <StatusBadge>、筛选项由 <FilterSelect> 从同一张表派生，
// 否则改一处文案就会出现「筛选写『已撤销』、徽标写『撤销』」。
// ⚠️ 键序 = 筛选下拉的选项顺序，别随手排序。
const WL_STATUS: StatusMap<FreeUserWhitelist["status"]> = {
  ACTIVE: { label: "生效中", tone: "success" },
  EXPIRED: { label: "已过期", tone: "muted" },
  REVOKED: { label: "已撤销", tone: "danger" },
};

// —— 注销申请 / C 端开票（2026-09-25）——
// 两处的共同形状：C 端已经在产生数据，运营端此前没有对应的动作面。
const LOGOFF_STATUS: StatusMap<LogoffItem["status"]> = {
  PENDING: { label: "冷静期内", tone: "warning" },
  CANCELLED: { label: "已撤销", tone: "muted" },
  DONE: { label: "已清除", tone: "danger" },
};

const CUINV_STATUS: StatusMap<CUserInvoiceRow["status"]> = {
  APPLIED: { label: "待受理", tone: "warning" },
  ISSUED: { label: "已开具", tone: "success" },
  REJECTED: { label: "已驳回", tone: "danger" },
};

/**
 * @form POST /api/user/cuser-invoices/{invoiceNo}/issue
 */
const ISSUE_FIELDS: FieldDef[] = [
  { key: "fileUrl", label: "发票文件地址", required: true, placeholder: "https://…/invoice.pdf",
    help: "开具后消费者在 App 里点开的就是这个地址；留空等于告诉他「已开具」却拿不到票" },
];

/**
 * @form POST /api/user/cuser-invoices/{invoiceNo}/reject
 */
const REJECT_FIELDS: FieldDef[] = [
  { key: "reason", label: "驳回原因", required: true, maxLength: 200, type: "textarea",
    placeholder: "例如：抬头与实名信息不符，请修改抬头后重新提交",
    help: "必填。只说「已驳回」，用户无从改正后重提 —— 他会做的事是再提一次" },
];

/**
 * @form POST /api/user/free-whitelist
 * @form POST /api/user/free-whitelist/{userNo}
 */
const WHITELIST_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, required: true, section: "用户", placeholder: "U3001" },
  // 昵称/手机是**读出参里的展示值**（后端从 NicknameLookup 批量回填），不是这张表单能写的东西。
  // 原先它们是输入框且昵称还 required —— 运营被逼着填一个会被丢掉的值，而抽屉标题里本来就有用户号。
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
const PKG_STATUS: StatusMap<RechargePackage["status"]> = {
  ENABLED: { label: "上架", tone: "success" },
  DISABLED: { label: "下架", tone: "muted" },
};
/**
 * @form POST /api/user/recharge-packages
 * @form POST /api/user/recharge-packages/{packageNo}
 */
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

// —— 钱包流水（后端 /api/user/wallets/{userNo}/txns 早已实现，此前运营端无处可看）——
const TXN_TYPE: StatusMap<WalletTxn["type"]> = {
  RECHARGE: { label: "充值", tone: "success" },
  SPEND: { label: "消费", tone: "danger" },
  REFUND: { label: "退款/入账", tone: "warning" },
  BONUS: { label: "赠额", tone: "outline" },
};

/**
 * @form POST /api/user/wallets
 * @form POST /api/user/wallets/{userNo}
 */
const WALLET_FIELDS: FieldDef[] = [
  { key: "userNo", label: "用户号", readOnlyOnEdit: true, placeholder: "U0001" },
  // 昵称/手机是**读出参里的展示值**（后端从 NicknameLookup 批量回填），不是这张表单能写的东西。
  // 原先它们是输入框且昵称还 required —— 运营被逼着填一个会被丢掉的值，而抽屉标题里本来就有用户号。
  { key: "balance", label: "余额", type: "number" },
  { key: "bonus", label: "赠额", type: "number" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

function UsersInner() {
  const paging = usePaging();
  const tabs = useNavTabs("/users", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, () => goTabReset());
  const [keyword, setKeyword] = useState("");
  const [walletForm, setWalletForm] = useState<Partial<Wallet> | null>(null);
  // 用户列表的批量选中（G3）。翻页/切 tab/改搜索都要清空——否则会对"看不见的行"下手。
  const [selectedUsers, setSelectedUsers] = useState<string[]>([]);
  // 充值套餐「显示已归档」（G1）。切 tab 复位，避免在别的 tab 残留一个看不见的过滤态。
  const [showArchived, setShowArchived] = useState(false);
  // 「充值订单与套餐」页签的两个视图：套餐（卖什么）/ 订单（卖出去了什么）
  const [rcView, setRcView] = useState<"packages" | "orders">("packages");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();

  const goTabReset = () => { paging.reset(); setSelectedUsers([]); setShowArchived(false); };
  // 翻页清勾选：第 2 页留着第 1 页的勾选，批量操作会作用到看不见的行上
  const goPage = (p: number) => { paging.setPage(p); setSelectedUsers([]); };
  const search = (v: string) => { setKeyword(v); paging.reset(); setSelectedUsers([]); };

  const canEditWallet = allow("user:wallet:update");
  const canEditWhitelist = allow("user:risk:update");
  const canEditPackage = allow("user:wallet:update");
  const { confirm, dialog } = useConfirm();

  const users = useQuery({
    queryKey: ["users", paging.page, paging.size, keyword],
    queryFn: () => api.listUsers({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "list",
  });

  // —— 用户详情抽屉（S4）：订单 / 钱包 / 风控一页看全 ——
  // 一个接口取全，不在页面里按用户号并发调五个列表 —— 列表的 keyword 是模糊匹配，
  // 前端拼装迟早捞出别人的记录，而抽屉里的每一条都必须就是它所属 tab 里的那一条。
  // 详情抽屉支持 ?no= 深链：「销售管理 › 会员」名单的「详情」跳过来直接打开
  const sp = useSearchParams();
  const [profileNo, setProfileNo] = useState<string | null>(() => sp.get("no"));
  useEffect(() => { const n = sp.get("no"); if (n) setProfileNo(n); }, [sp]);
  const profile = useQuery({
    queryKey: ["user-profile", profileNo],
    queryFn: () => api.getUserProfile(profileNo!),
    enabled: !!profileNo,
  });

  const wallets = useQuery({
    queryKey: ["wallets", paging.page, paging.size, keyword],
    queryFn: () => api.listWallets({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "wallets",
  });
  // —— 钱包流水抽屉：余额只是结果，运营真正要查的是「这钱怎么来怎么没的」——
  // 独立的 txnPage：抽屉分页不能和外层列表共用 page，否则关掉抽屉外层就跳到别的页去了。
  const [txnFor, setTxnFor] = useState<Wallet | null>(null);
  const txnPaging = usePaging();
  const [txnType, setTxnType] = useState("");
  const txns = useQuery({
    queryKey: ["wallet-txns", txnFor?.userNo, txnPaging.page, txnPaging.size, txnType],
    queryFn: () => api.listWalletTxns(txnFor!.userNo, { page: txnPaging.page, size: txnPaging.size, type: txnType || undefined }),
    placeholderData: keepPreviousData,
    enabled: !!txnFor,
  });

  const risks = useQuery({
    queryKey: ["user-risks", paging.page, paging.size, keyword],
    queryFn: () => api.listUserRisks({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "risk",
  });

  // —— 信用分调整（S2：权限码 user:risk:update 早已定义，风控页却没有调分动作）——
  // 抽屉里同时展示该用户的调分历史：金额/分值类操作没有留痕就等于没做。
  // 权限码核实过：SSOT《功能权限清单》§用户域「风控/黑名单 改」= user:risk:update（与拉黑同码）
  const canAdjustCredit = allow("user:risk:update");
  const [creditRow, setCreditRow] = useState<UserRisk | null>(null);
  const [creditDir, setCreditDir] = useState<"add" | "sub">("sub");
  const [creditValue, setCreditValue] = useState("");
  const [creditReason, setCreditReason] = useState("");
  const creditDelta = creditDir === "add" ? Number(creditValue || 0) : -Number(creditValue || 0);
  const creditAfter = (creditRow?.creditScore ?? 0) + creditDelta;
  const creditValid = Number.isInteger(Number(creditValue)) && Number(creditValue) > 0
    && creditAfter >= CREDIT_SCORE_MIN && creditAfter <= CREDIT_SCORE_MAX && !!creditReason.trim();
  const creditHistory = useQuery({
    queryKey: ["credit-score-changes", creditRow?.userNo],
    queryFn: () => api.listCreditScoreChanges({ cUserNo: creditRow!.userNo, size: RECENT_LIMIT }),
    enabled: !!creditRow,
  });
  const adjustCredit = useMutation({
    mutationFn: (v: { no: string; delta: number; reason: string }) =>
      api.adjustCreditScore(v.no, { delta: v.delta, reason: v.reason }),
    onSuccess: (r) => {
      notify.success(`信用分 ${r.change.before} → ${r.change.after}（${r.change.delta > 0 ? "+" : ""}${r.change.delta}）· ${r.change.changeNo}`);
      qc.invalidateQueries({ queryKey: ["user-risks"] });
      qc.invalidateQueries({ queryKey: ["users"] });
      qc.invalidateQueries({ queryKey: ["credit-score-changes"] });
      qc.invalidateQueries({ queryKey: ["user-profile"] });
      // 抽屉不关：就地显示调整后的分数/等级与新增的一条留痕，便于连续调整
      if (r.risk) setCreditRow(r.risk);
      setCreditValue("");
      setCreditReason("");
    },
  });
  const blacklisted = useQuery({
    queryKey: ["user-blacklist", paging.page, paging.size, keyword],
    queryFn: () => api.listUserBlacklist({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "blacklist",
  });

  // —— 免费用户白名单（B4）——
  const [wlReason, setWlReason] = useState("");
  const [wlStatus, setWlStatus] = useState("");
  const [wlForm, setWlForm] = useState<Partial<FreeUserWhitelist> | null>(null);
  const whitelist = useQuery({
    queryKey: ["free-whitelist", paging.page, paging.size, keyword, wlReason, wlStatus],
    queryFn: () => api.listFreeWhitelist({ page: paging.page, size: paging.size, keyword, reason: wlReason || undefined, status: wlStatus || undefined }),
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
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["recharge-packages", paging.page, paging.size, keyword, pkgStatus, showArchived],
    queryFn: () => api.listRechargePackages({ page: paging.page, size: paging.size, keyword, status: pkgStatus || undefined, showArchived }),
    placeholderData: keepPreviousData,
    enabled: tab === "recharge",
  });
  const savePkg = useMutation({
    mutationFn: (v: Partial<RechargePackage>) => api.saveRechargePackage(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recharge-packages"] }); notify.success(t("common.success")); setPkgForm(null); },
  });
  const archivePkg = useMutation({
    mutationFn: (no: string) => api.archiveRechargePackage(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recharge-packages"] }); notify.success("套餐已归档"); },
  });
  const unarchivePkg = useMutation({
    mutationFn: (no: string) => api.unarchiveRechargePackage(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recharge-packages"] }); notify.success("套餐已恢复"); },
  });

  const bl = useMutation({
    mutationFn: (v: { no: string; blacklisted: boolean }) => api.setBlacklist(v.no, v.blacklisted),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["users"] });
      qc.invalidateQueries({ queryKey: ["user-blacklist"] });
      qc.invalidateQueries({ queryKey: ["user-profile"] });
    },
  });

  // —— 批量拉黑（G3）——逐条调用；失败走全局 MutationCache.onError
  const canBlacklist = allow("user:risk:update");
  const batchBlacklist = useMutation({
    mutationFn: (nos: string[]) => Promise.all(nos.map((no) => api.setBlacklist(no, true))),
    onSuccess: (_r, nos) => {
      qc.invalidateQueries({ queryKey: ["users"] });
      qc.invalidateQueries({ queryKey: ["user-blacklist"] });
      notify.success(`已将 ${nos.length} 位用户加入黑名单`);
      setSelectedUsers([]);
    },
  });
  const askBatchBlacklist = async () => {
    const n = selectedUsers.length;
    if (!n) return;
    const ok = await confirm({
      title: `批量拉黑 ${n} 位用户`,
      desc: `将把已选的 ${n} 位用户加入黑名单，加入后无法借出充电宝；已在黑名单中的用户保持不变。可在「黑名单」页逐个解除。`,
      danger: true,
      confirmText: `确认拉黑 ${n} 位`,
    });
    if (ok) batchBlacklist.mutate(selectedUsers);
  };

  const saveWallet = useMutation({
    mutationFn: (v: Partial<Wallet>) => api.saveWallet(v),
    // 手工调余额会落一条调整流水，流水查询必须一并失效，否则抽屉里还是调整前的合计
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["wallets"] });
      qc.invalidateQueries({ queryKey: ["wallet-txns"] });
      // 详情抽屉里的钱包块与最近流水同源，一并失效
      qc.invalidateQueries({ queryKey: ["user-profile"] });
      notify.success(t("common.success"));
      setWalletForm(null);
    },
  });

  // 业务号列一律 body-strong（类型阶 txt-strong = 14/500）作扫描锚点；
  // 类型阶不与 font-*/leading-* 共存，否则那些工具类是死代码（见项目约定）。
  // 计数/金额列 text-right + tabular-nums：右对齐让位数对齐，整列不跳动（规范 §12.4）。
  const userCols: Column<CUser>[] = [
    { header: "用户号", cell: (u) => <span className="txt-strong tabular-nums">{u.cUserNo}</span> },
    // 头像 + 昵称走仓里已有的 AvatarLabel：没有头像时它自己退回首字母底色，
    // 不需要在这里判空。这是展示便利，不是缺陷修复。
    { header: "昵称", cell: (u) => <AvatarLabel src={u.avatar ?? undefined} name={u.nickname} size="sm" /> },
    { header: "手机", cell: (u) => <span className="text-muted-foreground tabular-nums">{u.phone}</span> },
    { header: "信用分", className: "text-right", cell: (u) => <span className="tabular-nums">{u.creditScore}</span> },
    { header: "订单数", className: "text-right", cell: (u) => <span className="tabular-nums">{u.orders}</span> },
    { header: "注册", cell: (u) => <span className="text-muted-foreground">{fmtTime(u.registeredAt)}</span> },
    { header: "状态", cell: (u) => <StatusBadge map={ACCOUNT_STATUS} value={u.blacklisted ? "BLACKLISTED" : "NORMAL"} /> },
    {
      header: "操作",
      // 「详情」只读，进得来这张表就有 user:cuser:read，故不额外判权；拉黑才要写权限
      cell: (u) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setProfileNo(u.cUserNo)}>详情</Button>
          {canBlacklist && (
            <Button size="sm" variant="outline" disabled={bl.isPending} onClick={() => bl.mutate({ no: u.cUserNo, blacklisted: !u.blacklisted })}>
              {u.blacklisted ? "解除拉黑" : "拉黑"}
            </Button>
          )}
        </div>
      ),
    },
  ];


  const riskCols: Column<UserRisk>[] = [
    { header: "风控号", cell: (r) => <span className="txt-strong tabular-nums">{r.riskNo}</span> },
    { header: "用户号", cell: (r) => <span className="tabular-nums">{r.userNo}</span> },
    { header: "昵称", cell: (r) => r.nickname },
    { header: "手机", cell: (r) => <span className="text-muted-foreground tabular-nums">{r.phone}</span> },
    { header: "信用分", className: "text-right", cell: (r) => <span className="tabular-nums">{r.creditScore}</span> },
    { header: "风险等级", cell: (r) => <StatusBadge map={RISK_LEVEL} value={r.riskLevel} /> },
    { header: "原因", cell: (r) => <span className="text-muted-foreground">{r.reason}</span> },
    { header: "标记时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.flaggedAt)}</span> },
    {
      header: "操作",
      cell: (r) => canAdjustCredit
        ? (
          <Button
            size="sm"
            variant="outline"
            disabled={adjustCredit.isPending}
            onClick={() => { setCreditRow(r); setCreditDir("sub"); setCreditValue(""); setCreditReason(""); }}
          >调整信用分</Button>
        )
        : <span className="text-muted-foreground">-</span>,
    },
  ];
  const blacklistCols: Column<UserBlacklist>[] = [
    { header: "黑名单号", cell: (b) => <span className="txt-strong tabular-nums">{b.blacklistNo}</span> },
    { header: "用户号", cell: (b) => <span className="tabular-nums">{b.userNo}</span> },
    { header: "昵称", cell: (b) => b.nickname },
    { header: "手机", cell: (b) => <span className="text-muted-foreground tabular-nums">{b.phone}</span> },
    { header: "原因", cell: (b) => b.reason },
    { header: "拉黑时间", cell: (b) => <span className="text-muted-foreground">{fmtTime(b.blacklistedAt)}</span> },
    // 合规要能回答「谁拉黑、谁放开」
    {
      header: "操作人",
      cell: (b) => (
        <span className="txt-caption text-muted-foreground">
          {b.blacklistedBy ?? "—"}
          {b.releasedBy ? ` → ${b.releasedBy}` : ""}
        </span>
      ),
    },
    { header: "状态", cell: (b) => <StatusBadge map={BL_STATUS} value={b.status} /> },
    {
      header: "操作",
      cell: (b) => canBlacklist && b.status === "ACTIVE"
        ? <Button size="sm" variant="outline" disabled={bl.isPending} onClick={() => bl.mutate({ no: b.userNo, blacklisted: false })}>解除</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];
  const walletCols: Column<Wallet>[] = [
    { header: "用户号", cell: (w) => <span className="txt-strong tabular-nums">{w.userNo}</span> },
    { header: "昵称", cell: (w) => w.nickname },
    { header: "余额", className: "text-right", cell: (w) => <span className="tabular-nums">{money(w.balance, w.currency)}</span> },
    { header: "赠额", className: "text-right", cell: (w) => <span className="tabular-nums">{money(w.bonus, w.currency)}</span> },
    { header: "币种", cell: (w) => <Badge tone="outline">{w.currency}</Badge> },
    // 用户价值画像四列：钱包页即可判断该用户值不值得挽留/补偿，不必再跳订单页
    { header: "订单数", className: "text-right", cell: (w) => <span className="tabular-nums">{w.orderCount}</span> },
    { header: "订单金额", className: "text-right", cell: (w) => <span className="tabular-nums">{money(w.orderAmount, w.currency)}</span> },
    { header: "充值次数", className: "text-right", cell: (w) => <span className="tabular-nums">{w.rechargeCount}</span> },
    { header: "充值金额", className: "text-right", cell: (w) => <span className="tabular-nums">{money(w.rechargeAmount, w.currency)}</span> },
    { header: "更新时间", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.updatedAt)}</span> },
    {
      header: t("common.actions"),
      // 「流水」只读，进得来这张表就有 user:wallet:read，故不再额外判权；调余额才要写权限
      cell: (w) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => { setTxnFor(w); txnPaging.reset(); setTxnType(""); }}>流水</Button>
          {canEditWallet && <Button size="sm" variant="outline" onClick={() => setWalletForm(w)}>调整余额</Button>}
        </div>
      ),
    },
  ];
  const txnCols: Column<WalletTxn>[] = [
    { header: "时间", cell: (x) => <span className="text-muted-foreground">{fmtTime(x.createdAt)}</span> },
    { header: "类型", cell: (x) => <StatusBadge map={TXN_TYPE} value={x.type} /> },
    { header: "事由", cell: (x) => x.title },
    // 金额带符号（IN 正 / OUT 负），正负同色区分：省得运营对着「方向」列心算。
    // 颜色不单独承载语义（规范 §11.4）：正负号本身就是非颜色线索，色盲用户照样能读。
    {
      header: "金额",
      className: "text-right",
      cell: (x) => (
        <span className={`tabular-nums ${x.amount >= 0 ? "text-[var(--success)]" : "text-[var(--destructive)]"}`}>
          {x.amount >= 0 ? "+" : "-"}{money(Math.abs(x.amount), x.currency)}
        </span>
      ),
    },
    { header: "关联单据", cell: (x) => x.bizNo ? <span className="tabular-nums">{x.bizNo}</span> : <span className="text-muted-foreground">{x.bizType || "-"}</span> },
    { header: "流水号", cell: (x) => <span className="text-muted-foreground tabular-nums">{x.txnNo}</span> },
  ];

  // —— 详情抽屉里的两张小表：订单沿用订单页的同一个状态徽标，次卡沿用发放时的枚举 ——
  const profileOrderCols: Column<RentOrder>[] = [
    { header: "订单号", cell: (o) => <span className="txt-strong tabular-nums">{o.orderNo}</span> },
    { header: "状态", cell: (o) => <OrderStatusBadge s={o.status} /> },
    { header: "点位/机柜", cell: (o) => <span className="text-muted-foreground">{o.locationName ?? "-"} · {o.cabinetNo}</span> },
    { header: "时长", className: "text-right", cell: (o) => <span className="tabular-nums">{o.durationMin != null ? `${o.durationMin} 分钟` : "-"}</span> },
    { header: "费用", className: "text-right", cell: (o) => <span className="tabular-nums">{money(o.feeAmount, o.currency)}</span> },
    { header: "开始时间", cell: (o) => <span className="text-muted-foreground">{fmtTime(o.rentStartAt)}</span> },
  ];
  const profileCardCols: Column<MemberCard>[] = [
    { header: "卡号", cell: (c) => <span className="txt-strong tabular-nums">{c.cardNo}</span> },
    { header: "类型", cell: (c) => <Badge tone="outline">{MEMBER_CARD_LABEL[c.cardType]}</Badge> },
    // 时长卡不限次，用「-」而不是 0：0 次会被读成「一次都不能用」
    { header: "次数", className: "text-right", cell: (c) => <span className="tabular-nums">{c.cardType === "TIMES" ? `${c.usedTimes}/${c.totalTimes}` : "-"}</span> },
    { header: "有效期", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.validFrom)} ~ {fmtTime(c.validTo)}</span> },
    { header: "状态", cell: (c) => <StatusBadge map={CARD_STATUS} value={c.status} /> },
    { header: "来源", cell: (c) => c.source === "GRANT" ? <span>运营发放 · {c.grantedBy}</span> : <span className="text-muted-foreground">用户自购</span> },
    { header: "事由", cell: (c) => <span className="text-muted-foreground">{c.note || "-"}</span> },
  ];

  // 过期/撤销整行灰显（B0 补丁 rowClassName）；dim 保留给需要额外弱化的单元格
  const dim = (w: FreeUserWhitelist, node: ReactNode) =>
    w.status === "ACTIVE" ? node : <span className="text-muted-foreground">{node}</span>;
  const whitelistCols: Column<FreeUserWhitelist>[] = [
    { header: "用户号", cell: (w) => dim(w, <span className="txt-strong tabular-nums">{w.userNo}</span>) },
    { header: "昵称", cell: (w) => dim(w, w.nickname) },
    { header: "手机", cell: (w) => <span className="text-muted-foreground tabular-nums">{w.phone}</span> },
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
    { header: "状态", cell: (w) => <StatusBadge map={WL_STATUS} value={w.status} /> },
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
    { header: "套餐号", cell: (r) => <span className="txt-strong tabular-nums">{r.packageNo}</span> },
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
    { header: "到账合计", className: "text-right", cell: (r) => <span className="tabular-nums">{money(r.payAmount + r.giftAmount, r.currency)}</span> },
    { header: "适用市场", cell: (r) => <div className="flex flex-wrap gap-1">{r.markets.split(",").filter(Boolean).map((m) => <Badge key={m} tone="outline">{m}</Badge>)}</div> },
    { header: "赠额有效期", className: "text-right", cell: (r) => <span className="tabular-nums">{r.validDays} 天</span> },
    { header: "排序", className: "text-right", cell: (r) => <span className="tabular-nums">{r.sortNo}</span> },
    { header: "状态", cell: (r) => <StatusBadge map={PKG_STATUS} value={r.status} /> },
    // 归档时间列只在「显示已归档」打开时出现，默认视图里整列都是 `-` 属于噪音
    ...(showArchived ? [{ header: "归档时间", cell: (r: RechargePackage) => <ArchivedAt at={r.archivedAt} /> }] : []),
    {
      header: t("common.actions"),
      cell: (r) => (
        <ArchiveActions
          archived={!!r.archivedAt}
          canWrite={canEditPackage}
          actions={<Button size="sm" variant="outline" onClick={() => setPkgForm(r)}>{t("common.edit")}</Button>}
          // 充值套餐不属于主数据强确认清单（机柜/站点/场地方/代理商/角色），不要求手输编号
          onArchive={async () => { if (await confirm(archiveConfirm("充值套餐", `${r.packageNo} ${r.name}`))) archivePkg.mutate(r.packageNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("充值套餐", `${r.packageNo} ${r.name}`))) unarchivePkg.mutate(r.packageNo); }}
        />
      ),
    },
  ];

  // 抽屉表头的余额取列表里的最新值（调完余额失效重拉后即刻同步），拿不到再退回打开时的快照
  const txnWallet = wallets.data?.list.find((w) => w.userNo === txnFor?.userNo) ?? txnFor;

  const pf = profile.data;
  // 详情里的两个入口都**跳去既有抽屉**，不在详情里另写一份表单。
  // 跳之前先关详情：两层 radix 抽屉叠着会互相抢焦点、遮罩还会叠成两层黑。
  const openTxnsFromProfile = (w: Wallet) => {
    setTxnFor(w);
    txnPaging.reset();
    setTxnType("");
    setProfileNo(null);
  };
  const openCreditFromProfile = (p: UserProfile) => {
    // 不在风控名单的用户也能调分（掉到阈值以下服务端会自动补一条风控记录）。
    // 调分抽屉只用到 用户号/昵称/手机/分数/等级 五个字段，名单外的用户缺 riskNo/reason/flaggedAt，
    // 留空即可 —— 调完服务端会把真正的风控记录带回来替换掉这份临时行。
    setCreditRow(p.risk ?? {
      riskNo: "", userNo: p.user.cUserNo, nickname: p.user.nickname, phone: p.user.phone,
      creditScore: p.user.creditScore, riskLevel: riskLevelOf(p.user.creditScore),
      reason: "", flaggedAt: "",
    });
    setCreditDir("sub");
    setCreditValue("");
    setCreditReason("");
    setProfileNo(null);
  };

  // —— 注销申请受理（user:logoff:read / :revoke）——
  const [logoffStatus, setLogoffStatus] = useState("");
  const canRevokeLogoff = allow("user:logoff:revoke");
  const logoffs = useQuery({
    queryKey: ["logoffs", paging.page, paging.size, keyword, logoffStatus],
    queryFn: () => api.listLogoffs({ page: paging.page, size: paging.size, keyword, status: logoffStatus }),
    placeholderData: keepPreviousData,
    enabled: tab === "logoffs",
  });
  const revokeLogoff = useMutation({
    mutationFn: (cUserNo: string) => api.revokeLogoff(cUserNo),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["logoffs"] }); notify.success("已代为撤销，用户在 App 里看到的也会同步"); },
  });
  const askRevokeLogoff = async (x: LogoffItem) => {
    const ok = await confirm({
      title: `撤销 ${x.cUserNo} 的注销申请`,
      desc: `冷静期至 ${x.coolingUntil}。撤销后账号恢复正常，用户在 App 里看到的状态也会跟着变。`,
      confirmText: "确认撤销",
    });
    if (ok) revokeLogoff.mutate(x.cUserNo);
  };

  const logoffCols: Column<LogoffItem>[] = [
    { header: "用户号", cell: (x) => <span className="txt-strong tabular-nums">{x.cUserNo}</span> },
    { header: "申请时间", cell: (x) => <span className="text-muted-foreground tabular-nums">{x.requestedAt}</span> },
    { header: "冷静期至", cell: (x) => <span className="tabular-nums">{x.coolingUntil}</span> },
    { header: "状态", cell: (x) => <StatusBadge map={LOGOFF_STATUS} value={x.status} /> },
    { header: "清除时间", cell: (x) => <span className="text-muted-foreground tabular-nums">{x.purgedAt ?? "-"}</span> },
    {
      header: t("common.actions"),
      // 只有冷静期内的能撤销。已清除的单摆个灰按钮比不摆更糟 —— 那是一个点了会失败的按钮
      cell: (x) => canRevokeLogoff && x.status === "PENDING"
        ? <Button size="sm" variant="outline" disabled={revokeLogoff.isPending} onClick={() => askRevokeLogoff(x)}>代为撤销</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  // —— C 端开票受理（user:invoice:read / :handle）——
  const [invStatus, setInvStatus] = useState("");
  const [issueForm, setIssueForm] = useState<{ invoiceNo: string; fileUrl: string } | null>(null);
  const [rejectForm, setRejectForm] = useState<{ invoiceNo: string; reason: string } | null>(null);
  const canHandleInvoice = allow("user:invoice:handle");
  const cuserInvoices = useQuery({
    queryKey: ["cuser-invoices", paging.page, paging.size, keyword, invStatus],
    queryFn: () => api.listCUserInvoices({ page: paging.page, size: paging.size, keyword, status: invStatus }),
    placeholderData: keepPreviousData,
    enabled: tab === "invoices",
  });
  const issueInv = useMutation({
    mutationFn: (v: { invoiceNo: string; fileUrl: string }) => api.issueCUserInvoice(v.invoiceNo, v.fileUrl),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["cuser-invoices"] }); setIssueForm(null); notify.success("已开具"); },
  });
  const rejectInv = useMutation({
    mutationFn: (v: { invoiceNo: string; reason: string }) => api.rejectCUserInvoice(v.invoiceNo, v.reason),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["cuser-invoices"] }); setRejectForm(null); notify.success("已驳回"); },
  });

  const cuserInvoiceCols: Column<CUserInvoiceRow>[] = [
    { header: "申请号", cell: (x) => <span className="txt-strong tabular-nums">{x.invoiceNo}</span> },
    { header: "申请人", cell: (x) => <span>{x.nickname ?? "-"}<span className="ms-2 text-muted-foreground tabular-nums">{x.cUserNo}</span></span> },
    { header: "抬头", cell: (x) => x.title },
    { header: "金额", cell: (x) => <span className="tabular-nums">{x.currency} {x.amount.toFixed(2)}</span> },
    { header: "状态", cell: (x) => <StatusBadge map={CUINV_STATUS} value={x.status} /> },
    // 驳回原因就显示在列表里：藏进详情的话，客服接到电话还得点一下才知道为什么被驳
    { header: "驳回原因", cell: (x) => <span className="text-muted-foreground">{x.rejectReason ?? "-"}</span> },
    { header: "受理人", cell: (x) => <span className="text-muted-foreground">{x.handledBy ?? "-"}</span> },
    { header: "申请时间", cell: (x) => <span className="text-muted-foreground tabular-nums">{x.appliedAt}</span> },
    {
      header: t("common.actions"),
      cell: (x) => canHandleInvoice && x.status === "APPLIED" ? (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setIssueForm({ invoiceNo: x.invoiceNo, fileUrl: "" })}>开具</Button>
          <Button size="sm" variant="outline" onClick={() => setRejectForm({ invoiceNo: x.invoiceNo, reason: "" })}>驳回</Button>
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const active = tab === "list" ? users
    : tab === "wallets" ? wallets
    : tab === "risk" ? risks
    : tab === "whitelist" ? whitelist
    : tab === "recharge" ? packages
    : tab === "logoffs" ? logoffs
    : tab === "invoices" ? cuserInvoices
    : blacklisted;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "list" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索昵称 / 手机 / 用户号"
            onExport={() => exportCsv<CUser>("用户", [
              { header: "用户号", value: (u) => u.cUserNo },
              { header: "昵称", value: (u) => u.nickname },
              { header: "手机", value: (u) => u.phone },
              { header: "信用分", value: (u) => u.creditScore },
              { header: "订单数", value: (u) => u.orders },
              { header: "注册", value: (u) => fmtTime(u.registeredAt) },
              { header: "状态", value: (u) => (u.blacklisted ? "黑名单" : "正常") },
            ], users.data?.list ?? [])}
            selectedCount={selectedUsers.length}
            batchActions={
              <Button size="sm" variant="destructive" disabled={batchBlacklist.isPending} onClick={askBatchBlacklist}>
                批量拉黑
              </Button>
            }
            onClearSelection={() => setSelectedUsers([])}
          />
          {!canBlacklist && <ReadOnlyNotice what="用户风控" perm="user:risk:update" note="不能拉黑（含批量拉黑）" />}
          <DataTable
            rowKey={(u: CUser) => u.cUserNo}
            columns={userCols}
            rows={users.data?.list}
            loading={users.isLoading} error={users.error} onRetry={users.refetch}
            selectable={canBlacklist}
            selectedKeys={selectedUsers}
            onSelectedChange={setSelectedUsers}
            empty="没有符合条件的用户——可能是搜索词太窄，或 C 端尚无用户注册；清空搜索再看一次"
          />
        </>
      )}
      {tab === "risk" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索用户号 / 昵称 / 手机"
            onExport={() => exportCsv<UserRisk>("风控用户", [
              { header: "风控号", value: (r) => r.riskNo },
              { header: "用户号", value: (r) => r.userNo },
              { header: "昵称", value: (r) => r.nickname },
              { header: "手机", value: (r) => r.phone },
              { header: "信用分", value: (r) => r.creditScore },
              { header: "风险等级", value: (r) => RISK_LEVEL[r.riskLevel].label },
              { header: "原因", value: (r) => r.reason },
              { header: "标记时间", value: (r) => fmtTime(r.flaggedAt) },
            ], risks.data?.list ?? [])}
          />
          {!canAdjustCredit && <ReadOnlyNotice what="用户风控" perm="user:risk:update" note="不能调整信用分" />}
          <DataTable rowKey={(r: UserRisk) => r.riskNo} columns={riskCols} rows={risks.data?.list} loading={risks.isLoading} error={risks.error} onRetry={risks.refetch}
            empty="暂无风控用户——没有用户触发风控规则，或风控规则尚未配置（系统设置 · 业务规则）" />
        </>
      )}
      {tab === "blacklist" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索用户号 / 昵称"
            onExport={() => exportCsv<UserBlacklist>("黑名单", [
              { header: "黑名单号", value: (b) => b.blacklistNo },
              { header: "用户号", value: (b) => b.userNo },
              { header: "昵称", value: (b) => b.nickname },
              { header: "手机", value: (b) => b.phone },
              { header: "原因", value: (b) => b.reason },
              { header: "拉黑时间", value: (b) => fmtTime(b.blacklistedAt) },
              { header: "状态", value: (b) => (b.status === "ACTIVE" ? "拉黑中" : "已解除") },
            ], blacklisted.data?.list ?? [])}
          />
          <DataTable rowKey={(b: UserBlacklist) => b.blacklistNo} columns={blacklistCols} rows={blacklisted.data?.list} loading={blacklisted.isLoading} error={blacklisted.error} onRetry={blacklisted.refetch}
            empty="暂无黑名单用户——没有用户被拉黑；可在「用户」页勾选后批量拉黑" />
        </>
      )}
      {tab === "whitelist" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
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
            <FilterSelect
              value={wlReason}
              onChange={(v) => { setWlReason(v); paging.reset(); }}
              allLabel="全部用途"
              options={REASON_OPTIONS}
              aria-label="按用途筛选"
            />
            {/* 选项由 WL_STATUS 派生：筛选项文案与状态列徽标文案永远同源 */}
            <FilterSelect
              value={wlStatus}
              onChange={(v) => { setWlStatus(v); paging.reset(); }}
              allLabel="全部状态"
              options={WL_STATUS}
              aria-label="按状态筛选"
            />
          </Toolbar>
          {!canEditWhitelist && <ReadOnlyNotice what="白名单维护" perm="user:risk:update" note="不能新增、编辑或撤销白名单" />}
          <DataTable
            // 行键用白名单编号而非用户号：同一个用户可以有多条白名单
            rowKey={(w: FreeUserWhitelist) => w.whitelistNo}
            columns={whitelistCols}
            rows={whitelist.data?.list}
            loading={whitelist.isLoading} error={whitelist.error} onRetry={whitelist.refetch}
            rowClassName={(w) => (w.status === "ACTIVE" ? undefined : "opacity-60")}
            empty="暂无免费用户——内测/VIP/BD 演示/商户自用需要免单时在此登记，登记后订单会落到「订单 · 免费订单」"
          />
        </>
      )}
      {tab === "recharge" && (
        <div className="mb-3 flex gap-2" role="group" aria-label="充值视图">
          <Button size="sm" variant={rcView === "packages" ? "default" : "outline"} onClick={() => setRcView("packages")}>充值套餐</Button>
          <Button size="sm" variant={rcView === "orders" ? "default" : "outline"} onClick={() => setRcView("orders")}>充值订单</Button>
        </div>
      )}
      {tab === "recharge" && rcView === "orders" && <RechargeOrdersPanel />}
      {tab === "recharge" && rcView === "packages" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
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
              ...(showArchived ? [{ header: "归档时间", value: (r: RechargePackage) => (r.archivedAt ? fmtTime(r.archivedAt) : "") }] : []),
            ], packages.data?.list ?? [])}
          >
            <FilterSelect
              value={pkgStatus}
              onChange={(v) => { setPkgStatus(v); paging.reset(); }}
              allLabel="全部状态"
              options={PKG_STATUS}
              aria-label="按状态筛选"
            />
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
          </Toolbar>
          {!canEditPackage && <ReadOnlyNotice what="充值套餐维护" perm="user:wallet:update" note="不能新增、编辑或归档套餐" />}
          <DataTable
            rowKey={(r: RechargePackage) => r.packageNo}
            columns={packageCols}
            // 已归档与已下架都整行弱化：归档优先（archivedRowClass 命中即返回）
            rowClassName={(p: RechargePackage) => archivedRowClass(p) ?? (p.status === "ENABLED" ? undefined : "opacity-60")}
            rows={packages.data?.list}
            loading={packages.isLoading} error={packages.error} onRetry={packages.refetch}
            empty={showArchived
              ? "没有套餐——包含已归档在内也没有记录；点右上「新增套餐」建一条"
              : "暂无充值套餐——先配置「充 X 送 Y」套餐，C 端钱包页才有充值选项；已归档的套餐可打开「显示已归档」查看"}
          />
        </>
      )}
      {tab === "wallets" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索昵称 / 用户号"
            onExport={() => exportCsv<Wallet>("钱包", [
              { header: "用户号", value: (w) => w.userNo },
              { header: "昵称", value: (w) => w.nickname },
              { header: "余额", value: (w) => money(w.balance, w.currency) },
              { header: "赠额", value: (w) => money(w.bonus, w.currency) },
              { header: "币种", value: (w) => w.currency },
              { header: "订单数", value: (w) => w.orderCount },
              { header: "订单金额", value: (w) => money(w.orderAmount, w.currency) },
              { header: "充值次数", value: (w) => w.rechargeCount },
              { header: "充值金额", value: (w) => money(w.rechargeAmount, w.currency) },
              { header: "更新时间", value: (w) => fmtTime(w.updatedAt) },
            ], wallets.data?.list ?? [])}
          />
          {!canEditWallet && <ReadOnlyNotice what="钱包调整" perm="user:wallet:update" note="不能手工调整余额或赠额" />}
          <DataTable rowKey={(w: Wallet) => w.userNo} columns={walletCols} rows={wallets.data?.list} loading={wallets.isLoading} error={wallets.error} onRetry={wallets.refetch}
            empty="暂无钱包记录——用户首次充值或产生余额后才会在此出现" />
        </>
      )}
      {/* 充值订单视图自带分页，共用分页条只服务套餐视图 */}
      {active.data && !(tab === "recharge" && rcView === "orders") && <Pagination page={paging.page} size={paging.size} total={active.data.total} onPage={goPage} onSize={paging.setSize} />}

      {tab === "logoffs" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索用户号"
            onExport={() => exportCsv<LogoffItem>("注销申请", [
              { header: "用户号", value: (x) => x.cUserNo },
              { header: "申请时间", value: (x) => x.requestedAt },
              { header: "冷静期至", value: (x) => x.coolingUntil },
              { header: "状态", value: (x) => LOGOFF_STATUS[x.status].label },
              { header: "清除时间", value: (x) => x.purgedAt ?? "" },
            ], logoffs.data?.list ?? [])}
          >
            <FilterSelect
              value={logoffStatus}
              onChange={(v) => { setLogoffStatus(v); paging.reset(); }}
              allLabel="全部状态"
              options={LOGOFF_STATUS}
              aria-label="按状态筛选"
            />
          </Toolbar>
          {!canRevokeLogoff && <ReadOnlyNotice what="注销申请" perm="user:logoff:revoke" note="不能代为撤销" />}
          <DataTable
            rowKey={(x: LogoffItem) => x.cUserNo}
            columns={logoffCols}
            rows={logoffs.data?.list}
            loading={logoffs.isLoading} error={logoffs.error} onRetry={logoffs.refetch}
            empty="没有注销申请——这是好事。若确信有人提交过，先把状态筛选清空再看一次"
          />
        </>
      )}
      {tab === "invoices" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={search}
            searchPlaceholder="搜索申请号 / 用户号 / 昵称 / 抬头"
            onExport={() => exportCsv<CUserInvoiceRow>("C端开票申请", [
              { header: "申请号", value: (x) => x.invoiceNo },
              { header: "用户号", value: (x) => x.cUserNo },
              { header: "昵称", value: (x) => x.nickname ?? "" },
              { header: "抬头", value: (x) => x.title },
              { header: "金额", value: (x) => x.amount },
              { header: "币种", value: (x) => x.currency },
              { header: "状态", value: (x) => CUINV_STATUS[x.status].label },
              { header: "驳回原因", value: (x) => x.rejectReason ?? "" },
              { header: "受理人", value: (x) => x.handledBy ?? "" },
              { header: "申请时间", value: (x) => x.appliedAt },
            ], cuserInvoices.data?.list ?? [])}
          >
            <FilterSelect
              value={invStatus}
              onChange={(v) => { setInvStatus(v); paging.reset(); }}
              allLabel="全部状态"
              options={CUINV_STATUS}
              aria-label="按状态筛选"
            />
          </Toolbar>
          {!canHandleInvoice && <ReadOnlyNotice what="C 端开票申请" perm="user:invoice:handle" note="不能开具或驳回" />}
          <DataTable
            rowKey={(x: CUserInvoiceRow) => x.invoiceNo}
            columns={cuserInvoiceCols}
            rows={cuserInvoices.data?.list}
            loading={cuserInvoices.isLoading} error={cuserInvoices.error} onRetry={cuserInvoices.refetch}
            empty="没有开票申请——C 端尚未有人提交，或状态筛选太窄"
          />
        </>
      )}

      <FormDrawer
        open={!!issueForm}
        onOpenChange={(o) => !o && setIssueForm(null)}
        titleNew={`开具发票 · ${issueForm?.invoiceNo ?? ""}`}
        titleEdit={`开具发票 · ${issueForm?.invoiceNo ?? ""}`}
        isEdit={false}
        fields={ISSUE_FIELDS}
        value={(issueForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setIssueForm(v as { invoiceNo: string; fileUrl: string })}
        onSubmit={() => issueForm && issueInv.mutate(issueForm)}
        submitting={issueInv.isPending}
      />

      <FormDrawer
        open={!!rejectForm}
        onOpenChange={(o) => !o && setRejectForm(null)}
        titleNew={`驳回开票申请 · ${rejectForm?.invoiceNo ?? ""}`}
        titleEdit={`驳回开票申请 · ${rejectForm?.invoiceNo ?? ""}`}
        isEdit={false}
        fields={REJECT_FIELDS}
        value={(rejectForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRejectForm(v as { invoiceNo: string; reason: string })}
        onSubmit={() => rejectForm && rejectInv.mutate(rejectForm)}
        submitting={rejectInv.isPending}
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

      {/*
        钱包流水抽屉（GET /api/user/wallets/{userNo}/txns）。
        不设「变动后余额」列：后端流水行里没有这个字段，而分页只拿到当页，
        跨页自行累加必然算错——宁可不显示，也不给运营一个看着像真的错数。
        表头把当前余额/赠额摆出来，配合金额的正负号，够判断「这钱怎么来怎么没的」。
      */}
      <Drawer
        open={!!txnFor}
        onOpenChange={(o) => { if (!o) { setTxnFor(null); txnPaging.reset(); setTxnType(""); } }}
        title={txnWallet ? `钱包流水 · ${txnWallet.userNo} ${txnWallet.nickname}` : ""}
        desc="金额带符号：正为入账、负为出账；最新在前"
        width="w-[760px]"
      >
        {txnWallet && (
          <>
            <Field label="当前余额 / 赠额">
              <span className="tabular-nums">{money(txnWallet.balance, txnWallet.currency)}</span>
              {" · 赠额 "}
              <span className="tabular-nums">{money(txnWallet.bonus, txnWallet.currency)}</span>
            </Field>
            <Field label="按类型筛选">
              <FilterSelect
                className="w-full"
                value={txnType}
                onChange={(v) => { setTxnType(v); txnPaging.reset(); }}
                /* 选项由 TXN_TYPE 派生：流水类型的筛选项与徽标文案同源，改文案只改映射表 */
                options={TXN_TYPE}
                allLabel="全部类型"
                aria-label="按流水类型筛选"
              />
            </Field>
            <DataTable
              rowKey={(x: WalletTxn) => x.txnNo}
              columns={txnCols}
              rows={txns.data?.list}
              loading={txns.isLoading} error={txns.error} onRetry={txns.refetch}
              empty={txnType
                ? "该类型下没有流水——清掉类型筛选再看一次"
                : "暂无钱包流水——该用户还没有充值、消费或退款记录"}
            />
            {txns.data && <Pagination page={txnPaging.page} size={txnPaging.size} total={txns.data.total} onPage={txnPaging.setPage} onSize={txnPaging.setSize} />}
          </>
        )}
      </Drawer>

      {/* 调分抽屉：加分/减分 + 分值 + 原因必填；上下限在此先拦一道，mock/后端仍会兜底拒绝 */}
      <Drawer
        open={!!creditRow}
        onOpenChange={(o) => !o && setCreditRow(null)}
        title={creditRow ? `调整信用分 · ${creditRow.userNo} ${creditRow.nickname}` : ""}
        desc={`信用分范围 ${CREDIT_SCORE_MIN}~${CREDIT_SCORE_MAX}；调整后按阈值重算风险等级，每次调整永久留痕`}
        footer={
          creditRow && (
            <Button
              variant={creditDir === "sub" ? "destructive" : "default"}
              disabled={adjustCredit.isPending || !creditValid}
              onClick={() => adjustCredit.mutate({ no: creditRow.userNo, delta: creditDelta, reason: creditReason })}
            >确认{creditDir === "add" ? "加分" : "减分"}</Button>
          )
        }
      >
        {creditRow && (
          <>
            <Field label="用户 / 手机">{creditRow.userNo} · {creditRow.phone}</Field>
            <Field label="当前信用分 / 风险等级">
              <span className="tabular-nums">{creditRow.creditScore}</span>
              {" · "}
              <StatusBadge map={RISK_LEVEL} value={creditRow.riskLevel} />
            </Field>
            <Field label="调整方向">
              <Select className="w-full" value={creditDir} onChange={(e) => setCreditDir(e.target.value as "add" | "sub")}>
                <option value="sub">减分（违规/风险行为）</option>
                <option value="add">加分（申诉成立/良好履约）</option>
              </Select>
            </Field>
            <Field label={`调整分值（正整数，${CREDIT_SCORE_MIN}~${CREDIT_SCORE_MAX} 之内）`}>
              <Input type="number" min="1" step="1" value={creditValue} placeholder="如 20" onChange={(e) => setCreditValue(e.target.value)} />
            </Field>
            <Field label="调整后">
              {creditValue
                ? (
                  <span>
                    <span className="tabular-nums">{creditRow.creditScore} → {creditAfter}</span>
                    {" · "}
                    <StatusBadge map={RISK_LEVEL} value={riskLevelOf(creditAfter)} />
                    {(creditAfter < CREDIT_SCORE_MIN || creditAfter > CREDIT_SCORE_MAX) && (
                      <span className="ml-2 text-[var(--destructive)]">超出 {CREDIT_SCORE_MIN}~{CREDIT_SCORE_MAX}，无法提交</span>
                    )}
                  </span>
                )
                : <span className="text-muted-foreground">填写分值后显示</span>}
            </Field>
            <Field label="联动口径">
              分数低于 {RISK_MEDIUM_BELOW} 且该用户尚不在风控名单时，会自动补一条风控记录（进观察名单）；
              分数回升不会自动移出名单，只把等级降为「低风险」——名单是审计痕迹，移出需人工操作。
            </Field>
            <Field label="调整原因（必填）">
              <Input value={creditReason} placeholder="写清为什么调分，将随变更记录永久留痕" onChange={(e) => setCreditReason(e.target.value)} />
            </Field>
            <Field label="调分历史">
              <Timeline
                loading={creditHistory.isLoading}
                empty="无调分记录——该用户的信用分未被人工调整过"
                items={(creditHistory.data?.list ?? []).map((x) => ({
                  key: x.changeNo,
                  badge: { label: x.delta > 0 ? `+${x.delta}` : String(x.delta), tone: x.delta > 0 ? "success" as const : "danger" as const },
                  meta: `${x.changeNo} · ${fmtTime(x.createdAt)} · ${x.operatorName}`,
                  change: <span className="tabular-nums">{x.before} → {x.after}</span>,
                  text: x.reason,
                }))}
              />
            </Field>
          </>
        )}
      </Drawer>

      {/*
        用户详情抽屉（S4：F2 → F3）。一屏摆全「档案 / 风控 / 钱包 / 会员次卡 / 订单」。
        三条自律：
          ① 数据由 getUserProfile 一次取回 —— 页面不按用户号去各列表接口拼，模糊关键词会串人；
          ② 每一块的记录就是对应 tab 里的那一批（订单徽标都复用订单页的 OrderStatusBadge）；
          ③ 抽屉里不重复实现写操作 —— 「全部流水」「调整信用分」都跳去既有抽屉，
             同一个动作两套表单必然长歪。
        不逐块判读权限：本页四个 tab 自己就没判（页面入口已由 user:cuser:read 把住），
        逐块判会造出「tab 里看得见、抽屉里看不见」的新矛盾。
      */}
      <Drawer
        open={!!profileNo}
        onOpenChange={(o) => { if (!o) setProfileNo(null); }}
        title={pf ? `用户详情 · ${pf.user.cUserNo} ${pf.user.nickname}` : `用户详情 · ${profileNo ?? ""}`}
        desc="订单 / 钱包 / 风控一页看全；各块记录与对应 tab 完全一致"
        width="w-[900px]"
        footer={pf && (
          <>
            {pf.wallet && (
              <Button variant="secondary" onClick={() => openTxnsFromProfile(pf.wallet!)}>查看全部流水</Button>
            )}
            {canAdjustCredit && <Button onClick={() => openCreditFromProfile(pf)}>调整信用分</Button>}
          </>
        )}
      >
        {profile.isLoading && <span className="text-muted-foreground">加载中…</span>}
        {pf && (
          <>
            <div className="grid grid-cols-3 gap-4">
              <Field className="mb-0" label="用户号 / 注册时间">
                <span className="tabular-nums">{pf.user.cUserNo}</span>
                <div className="text-xs text-muted-foreground">{fmtTime(pf.user.registeredAt)}</div>
              </Field>
              <Field className="mb-0" label="昵称 / 手机">
                {pf.user.nickname}
                <div className="text-xs text-muted-foreground">{pf.user.phone}</div>
              </Field>
              <Field className="mb-0" label="账号状态">
                {pf.user.blacklisted ? <Badge tone="danger">黑名单</Badge> : <Badge tone="success">正常</Badge>}
              </Field>
              <Field className="mb-0" label="信用分 / 风险等级">
                <span className="tabular-nums">{pf.user.creditScore}</span>
                {" · "}
                <StatusBadge map={RISK_LEVEL} value={riskLevelOf(pf.user.creditScore)} />
              </Field>
              <Field className="mb-0" label="订单 / 累计消费">
                <span className="tabular-nums">{pf.orderStats.count} 单 · {money(pf.orderStats.amount, pf.orderStats.currency)}</span>
                <div className="text-xs text-muted-foreground">进行中 {pf.orderStats.openCount} 单</div>
              </Field>
              <Field className="mb-0" label="钱包余额 / 赠额">
                {pf.wallet
                  ? <span className="tabular-nums">{money(pf.wallet.balance, pf.wallet.currency)} · 赠额 {money(pf.wallet.bonus, pf.wallet.currency)}</span>
                  : <span className="text-muted-foreground">无钱包记录</span>}
              </Field>
            </div>

            <h3 className="mb-2 mt-6 text-sm font-medium">风控</h3>
            {pf.risk
              ? (
                <Field label="风控名单">
                  <StatusBadge map={RISK_LEVEL} value={pf.risk.riskLevel} />
                  {" "}
                  <span className="text-muted-foreground">{pf.risk.riskNo} · {pf.risk.reason} · 标记于 {fmtTime(pf.risk.flaggedAt)}</span>
                </Field>
              )
              : <Field label="风控名单"><span className="text-muted-foreground">不在风控名单 —— 未触发风控规则，或分数从未掉到 {RISK_MEDIUM_BELOW} 以下</span></Field>}
            <Field label="拉黑记录">
              <Timeline
                empty="从未被拉黑"
                items={pf.blacklist.map((b) => ({
                  key: b.blacklistNo,
                  badge: { label: b.status === "ACTIVE" ? "拉黑中" : "已解除", tone: b.status === "ACTIVE" ? "danger" as const : "muted" as const },
                  meta: `${b.blacklistNo} · ${fmtTime(b.blacklistedAt)}${b.releasedAt ? ` · 解除于 ${fmtTime(b.releasedAt)}` : ""}`,
                  text: b.reason,
                }))}
              />
            </Field>
            {pf.whitelist && (
              <Field label="免费用户白名单">
                <StatusBadge map={WL_STATUS} value={pf.whitelist.status} />
                {" "}
                <span className="text-muted-foreground">
                  {REASON_LABEL[pf.whitelist.reason]} · {QUOTA_LABEL[pf.whitelist.quotaType]}
                  {pf.whitelist.quotaType !== "UNLIMITED" && ` ${pf.whitelist.usedValue}/${pf.whitelist.quotaValue}`}
                  {" · "}{pf.whitelist.validFrom} ~ {pf.whitelist.validTo} · 授予人 {pf.whitelist.grantedBy}
                </span>
              </Field>
            )}
            <Field label="调分历史">
              <Timeline
                empty="无调分记录——该用户的信用分未被人工调整过"
                items={pf.creditChanges.map((x) => ({
                  key: x.changeNo,
                  badge: { label: x.delta > 0 ? `+${x.delta}` : String(x.delta), tone: x.delta > 0 ? "success" as const : "danger" as const },
                  meta: `${x.changeNo} · ${fmtTime(x.createdAt)} · ${x.operatorName}`,
                  change: <span className="tabular-nums">{x.before} → {x.after}</span>,
                  text: x.reason,
                }))}
              />
            </Field>

            <h3 className="mb-2 mt-6 text-sm font-medium">钱包</h3>
            {pf.wallet
              ? (
                <>
                  <Field label="累计充值 / 累计订单">
                    <span className="tabular-nums">
                      {pf.wallet.rechargeCount} 次 · {money(pf.wallet.rechargeAmount, pf.wallet.currency)}
                      {" ／ "}
                      {pf.wallet.orderCount} 单 · {money(pf.wallet.orderAmount, pf.wallet.currency)}
                    </span>
                  </Field>
                  <Field label={`最近 ${PROFILE_RECENT_TXNS} 条流水（全部走「查看全部流水」）`}>
                    <DataTable rowKey={(x: WalletTxn) => x.txnNo} columns={txnCols} rows={pf.walletTxns}
                      empty="暂无钱包流水——该用户还没有充值、消费或退款记录" />
                  </Field>
                </>
              )
              : <span className="text-muted-foreground">该用户还没有钱包记录——首次充值或产生余额后才会有。</span>}

            <h3 className="mb-2 mt-6 text-sm font-medium">会员与次卡</h3>
            {pf.member
              ? (
                <Field label="会员">
                  <StatusBadge map={LEVEL} value={pf.member.level} />
                  {" "}
                  <span className="tabular-nums">{Math.round(pf.member.points)} 积分</span>
                  {" · "}
                  <span className="text-muted-foreground">
                    {pf.member.cardType === MEMBER_CARD_NONE ? "当前无生效次卡" : `${pf.member.cardType} · 到期 ${fmtTime(pf.member.expireAt)}`}
                  </span>
                </Field>
              )
              : <Field label="会员"><span className="text-muted-foreground">未开通会员</span></Field>}
            <DataTable rowKey={(c: MemberCard) => c.cardNo} columns={profileCardCols} rows={pf.cards}
              empty="该用户没有次卡——可在「会员/次卡」页发放" />

            <h3 className="mb-2 mt-6 text-sm font-medium">订单（{pf.orderStats.count} 单）</h3>
            <DataTable rowKey={(o: RentOrder) => o.orderNo} columns={profileOrderCols} rows={pf.orders}
              empty="该用户还没有订单——注册后未借出过充电宝" />
          </>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}

export default function UsersPage() {
  return <Suspense fallback={null}><UsersInner /></Suspense>;
}
