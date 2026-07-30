"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination, StatCard } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column, type SortDir } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { EnabledBadge } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Input, Select } from "@/components/ui/input";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions, archiveConfirm, unarchiveConfirm } from "@/components/archive";
import { Check, X } from "lucide-react";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type { Vendor, AccessMode, NotifyTemplate, DictEntry, Region, SysParam, OpenApiApp, MarketCountry, PaymentChannel, PageResult } from "@/lib/types";
import type {
  NotifyLog, NotifyBlacklist, LoginSetting, AppVersion, BankEntry, ProblemEntry, TaxSetting,
  WithdrawRule, ReservationRule, BillingDefaultRule,
} from "@/lib/types";

const SIZE = 10;
// tab 顺序与 lib/nav.ts 的 /system 叶子顺序一致（接入与支付 → 消息触达 → 业务规则 → 基础字典 → 开放与市场），
// 否则从菜单深链过来会觉得"页里的位置和菜单里的位置对不上"。
const TABS = [
  { key: "vendors", label: "供应商接入" },
  // 支付渠道：竞品 7 个渠道各占一菜单，我们合并为一页（列表 + 各自配置抽屉）
  { key: "payment", label: "支付渠道" },
  { key: "notify", label: "通知模板" },
  { key: "notify-log", label: "发送记录", phase: 2 as const },
  { key: "notify-blacklist", label: "触达拉黑", phase: 2 as const },
  { key: "rules", label: "业务规则", phase: 2 as const },
  { key: "login", label: "登录设置", phase: 2 as const },
  { key: "app-version", label: "应用版本", phase: 2 as const },
  { key: "dict", label: "参数字典" },
  { key: "region", label: "地区库" },
  { key: "banks", label: "银行管理", phase: 2 as const },
  { key: "problems", label: "问题管理", phase: 2 as const },
  { key: "params", label: "系统参数" },
  { key: "tax", label: "税率与发票", phase: 3 as const },
  { key: "markets", label: "多国家市场", phase: 3 as const },
  { key: "openapi", label: "OpenAPI 应用", phase: 3 as const },
];

const MODE_LABEL: Record<AccessMode, string> = { TCP: "TCP 私有协议", MQTT: "MQTT 直连", HTTP_API: "HTTP 云对接" };
const CHANNEL_LABEL: Record<NotifyTemplate["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "推送", WHATSAPP: "WhatsApp" };
const LANG_LABEL: Record<NotifyTemplate["lang"], string> = { ar: "阿拉伯语", en: "英语" };

const NOTIFY_FIELDS: FieldDef[] = [
  { key: "templateNo", label: "模板号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "订单完成通知" },
  { key: "channel", label: "渠道", type: "select", options: [{ value: "SMS", label: "短信" }, { value: "EMAIL", label: "邮件" }, { value: "PUSH", label: "推送" }, { value: "WHATSAPP", label: "WhatsApp" }] },
  { key: "lang", label: "语言", type: "select", options: [{ value: "ar", label: "阿拉伯语" }, { value: "en", label: "英语" }] },
  { key: "status", label: "状态", type: "select", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
];

const DICT_FIELDS: FieldDef[] = [
  { key: "dictNo", label: "字典号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "group", label: "分组", placeholder: "order_status" },
  { key: "code", label: "编码", placeholder: "PAID" },
  { key: "label", label: "标签", placeholder: "已支付" },
  { key: "sort", label: "排序", type: "number" },
  { key: "enabled", label: "启用", type: "switch" },
];

const REGION_FIELDS: FieldDef[] = [
  { key: "regionId", label: "区域 ID", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "迪拜" },
  { key: "parent", label: "上级区域", placeholder: "阿联酋" },
  { key: "level", label: "层级", type: "number" },
  { key: "cityCount", label: "城市数", type: "number" },
];

const PARAM_FIELDS: FieldDef[] = [
  { key: "paramKey", label: "参数键", readOnlyOnEdit: true, placeholder: "order.timeout.minutes" },
  { key: "label", label: "说明", placeholder: "订单超时分钟数" },
  { key: "value", label: "取值", placeholder: "30" },
  { key: "groupName", label: "分组", placeholder: "订单" },
];

// 密钥类字段一律 password 型 + 掩码占位，前端永不承载真实密钥（真实值仅后端保管）
const PAYMENT_FIELDS: FieldDef[] = [
  { key: "channelCode", label: "渠道码", readOnlyOnEdit: true, placeholder: "NEARPAY / STRIPE / PAYPAL" },
  { key: "channelName", label: "渠道名称", placeholder: "NearPay（聚合收单）" },
  { key: "mode", label: "接入模式", type: "select", options: [{ value: "DELEGATED", label: "委托" }, { value: "DIRECT", label: "直连" }] },
  { key: "status", label: "状态", type: "select", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "countries", label: "适用国家", placeholder: "AE,SA" },
  { key: "currencies", label: "币种", placeholder: "AED,SAR" },
  { key: "capabilities", label: "能力", placeholder: "支付,退款,预授权,分账" },
  { key: "apiBase", label: "API 基址", placeholder: "https://api.nearpay.example" },
  { key: "merchantId", label: "商户号", placeholder: "MID-AE-100286" },
  { key: "apiKeyMasked", label: "API 密钥（掩码）", type: "password", placeholder: "sk_test_****" },
];

// 国家码是业务主键（ISO alpha-2），新增必填、编辑只读
const MARKET_FIELDS: FieldDef[] = [
  { key: "countryCode", label: "国家码（ISO alpha-2）", readOnlyOnEdit: true, placeholder: "AE" },
  { key: "name", label: "国家名称", placeholder: "阿联酋" },
  { key: "currency", label: "币种", placeholder: "AED" },
  { key: "timezone", label: "时区", placeholder: "Asia/Dubai" },
  { key: "compliance", label: "合规主体", placeholder: "Neargo FZ-LLC / 筹备中 / 规划" },
  { key: "cityCount", label: "开城数", type: "number" },
  { key: "status", label: "状态", type: "select", options: [{ value: "LIVE", label: "已开城" }, { value: "PILOT", label: "试点" }, { value: "PLANNED", label: "规划" }] },
];

const OPENAPI_FIELDS: FieldDef[] = [
  { key: "appNo", label: "应用号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "名称", placeholder: "合作方对接" },
  { key: "appKey", label: "AppKey", placeholder: "ak_xxx" },
  { key: "rateLimit", label: "限流（次/秒）", type: "number" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
];

// ============================================================================
// 批次 B2/B3/B5 · 系统设置 8 个待建 tab（规格 §9~§16）
// 统一走 B0 补齐的共享组件能力：section 分区 / textarea / date / 字段级校验 /
// disabledWhen 联动禁用 / help 说明 / Toolbar 导出槽 / useConfirm 二次确认 / DataTable 排序。
// 勿在本区块手搓控件（TDD-运营端前端补全方案 §八-1）。
// ============================================================================

// —— §9 发送记录 ——
const LOG_CHANNEL: Record<NotifyLog["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "Push", WHATSAPP: "WhatsApp" };
const LOG_CHANNEL_OPTIONS = Object.entries(LOG_CHANNEL).map(([value, label]) => ({ value, label }));
const LOG_STATUS_OPTIONS = [{ value: "SENT", label: "已发送" }, { value: "FAILED", label: "发送失败" }];
/** 启用/停用二选一，多个字典 tab 共用 */
const ENABLED_OPTIONS = [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }];
const BANK_COUNTRY_OPTIONS = [
  { value: "AE", label: "阿联酋 AE" }, { value: "SA", label: "沙特 SA" }, { value: "QA", label: "卡塔尔 QA" },
  { value: "KW", label: "科威特 KW" }, { value: "EG", label: "埃及 EG" },
];
const BANK_CURRENCY_OPTIONS = ["AED", "SAR", "QAR", "KWD", "EGP"].map((c) => ({ value: c, label: c }));
const LOG_SCENE_HINT = "OTP / 借出 / 归还 / 扣费 / 告警";

// —— §10 触达拉黑 ——
const BL_CHANNEL: Record<NotifyBlacklist["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "Push", WHATSAPP: "WhatsApp", ALL: "全渠道" };
const BL_REASON: StatusMap<NotifyBlacklist["reason"]> = {
  USER_OPT_OUT: { label: "用户退订", tone: "muted" },
  HARD_BOUNCE: { label: "硬退信/无效号", tone: "warning" },
  ABUSE: { label: "滥用/投诉", tone: "danger" },
  MANUAL: { label: "人工拉黑", tone: "outline" },
};
const BL_CHANNEL_OPTIONS = [
  { value: "SMS", label: "短信" }, { value: "EMAIL", label: "邮件" }, { value: "PUSH", label: "Push" },
  { value: "WHATSAPP", label: "WhatsApp" }, { value: "ALL", label: "全渠道" },
];
const BLACKLIST_FIELDS: FieldDef[] = [
  { key: "blockNo", label: "拉黑号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "拉黑对象" },
  {
    key: "target", label: "目标（号码 / 邮箱）", required: true, maxLength: 64, section: "拉黑对象",
    placeholder: "+9715012345678 或 name@example.ae",
    help: "落库时按脱敏规则处理，列表只展示脱敏后的值",
  },
  { key: "channel", label: "渠道", type: "select", required: true, section: "拉黑对象", options: BL_CHANNEL_OPTIONS, help: "选「全渠道」= 该目标不再接收任何触达" },
  {
    key: "reason", label: "原因", type: "select", required: true, section: "处置",
    options: [
      { value: "USER_OPT_OUT", label: "用户退订" }, { value: "HARD_BOUNCE", label: "硬退信/无效号" },
      { value: "ABUSE", label: "滥用/投诉" }, { value: "MANUAL", label: "人工拉黑" },
    ],
    help: "枚举而非自由文本：退订来源要能统计",
  },
  { key: "blockedBy", label: "操作人", required: true, maxLength: 30, section: "处置", placeholder: "风控值班组" },
  { key: "expireAt", label: "到期时间", type: "date", section: "处置", help: "留空 = 永久拉黑" },
];

// —— §12 登录设置 ——
const LOGIN_FIELDS: FieldDef[] = [
  {
    key: "country", label: "国家码", readOnlyOnEdit: true, required: true, section: "适用范围",
    placeholder: "AE / SA / *",
    pattern: { re: "^(\\*|[A-Za-z]{2})$", msg: "国家码需为 ISO alpha-2（如 AE），或 * 表示默认档" },
    help: "* = 默认档，未单独配置的国家走它",
  },
  { key: "countryName", label: "国家名称", required: true, maxLength: 30, section: "适用范围", placeholder: "阿联酋" },
  { key: "otpEnabled", label: "短信验证码登录", type: "switch", section: "登录方式" },
  { key: "passwordEnabled", label: "密码登录", type: "switch", section: "登录方式" },
  { key: "appleEnabled", label: "Apple 登录", type: "switch", section: "第三方登录" },
  { key: "googleEnabled", label: "Google 登录", type: "switch", section: "第三方登录" },
  // 联动：关掉 OTP 登录，两个 OTP 策略字段自动禁用并清空——避免留下不生效的脏配置
  { key: "otpExpireSec", label: "OTP 有效期（秒）", type: "number", required: true, min: 60, max: 1800, section: "OTP 策略", disabledWhen: (v) => !v.otpEnabled, help: "60~1800 秒；关闭 OTP 登录后不适用" },
  { key: "otpDailyLimit", label: "单用户日发送上限（条）", type: "number", required: true, min: 1, max: 50, section: "OTP 策略", disabledWhen: (v) => !v.otpEnabled, help: "防刷与短信成本的主要闸门" },
  { key: "forceRealName", label: "强制实名", type: "switch", section: "合规", help: "沙特等地监管要求；开启后未实名用户不能借出" },
];

// —— §13 应用版本 ——
const PLATFORM_LABEL: Record<AppVersion["platform"], string> = { IOS: "iOS", ANDROID: "Android", H5: "H5 / 小程序" };
const PLATFORM_OPTIONS = Object.entries(PLATFORM_LABEL).map(([value, label]) => ({ value, label }));
const VERSION_STATUS: StatusMap<AppVersion["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  RELEASED: { label: "已发布", tone: "success" },
  ROLLBACK: { label: "已回滚", tone: "danger" },
};
const VERSION_FIELDS: FieldDef[] = [
  { key: "platform", label: "平台", type: "select", required: true, readOnlyOnEdit: true, section: "基本信息", options: [{ value: "IOS", label: "iOS" }, { value: "ANDROID", label: "Android" }, { value: "H5", label: "H5 / 小程序" }] },
  {
    key: "versionNo", label: "版本号", required: true, readOnlyOnEdit: true, section: "基本信息", placeholder: "1.5.0",
    pattern: { re: "^\\d+\\.\\d+\\.\\d+$", msg: "版本号格式为 x.y.z（如 1.5.0）" },
    help: "平台 + 版本号构成业务键：同一版本号在 iOS / Android 是两条记录",
  },
  { key: "buildNo", label: "构建号", type: "number", required: true, min: 1, section: "基本信息", placeholder: "1500" },
  { key: "status", label: "状态", type: "select", required: true, section: "基本信息", options: [{ value: "DRAFT", label: "草稿" }, { value: "RELEASED", label: "已发布" }, { value: "ROLLBACK", label: "已回滚" }] },
  { key: "releaseNote", label: "更新说明（中文）", type: "textarea", rows: 3, required: true, maxLength: 200, section: "三语更新说明", placeholder: "支持信用免押借出；修复扫码超时。" },
  { key: "releaseNoteEn", label: "更新说明（English）", type: "textarea", rows: 3, maxLength: 300, section: "三语更新说明" },
  { key: "releaseNoteAr", label: "更新说明（العربية）", type: "textarea", rows: 3, maxLength: 300, section: "三语更新说明" },
  { key: "forceUpdate", label: "强制更新", type: "switch", section: "发布控制", help: "开启后必须填「最低支持版本」，低于该版本的用户会被拦在启动页" },
  {
    key: "minSupported", label: "最低支持版本", section: "发布控制", placeholder: "1.4.3",
    pattern: { re: "^\\d+\\.\\d+\\.\\d+$", msg: "版本号格式为 x.y.z（如 1.4.3）" },
    help: "强更时必填（提交时校验）",
  },
  { key: "rolloutPercent", label: "灰度比例（%）", type: "number", required: true, min: 0, max: 100, section: "发布控制", help: "0~100；先小比例观察崩溃率再放量" },
  { key: "downloadUrl", label: "下载地址", required: true, maxLength: 200, section: "发布控制", placeholder: "https://apps.apple.com/app/id..." },
];

// —— §14 银行管理 ——
const BANK_FIELDS: FieldDef[] = [
  { key: "bankCode", label: "银行代码", required: true, readOnlyOnEdit: true, maxLength: 12, section: "基本信息", placeholder: "ENBD", help: "业务键，自动转大写；建档后不可改" },
  { key: "bankName", label: "银行名称（中文）", required: true, maxLength: 40, section: "基本信息", placeholder: "阿联酋国民银行" },
  { key: "bankNameEn", label: "银行名称（English）", required: true, maxLength: 60, section: "基本信息", placeholder: "Emirates NBD" },
  { key: "status", label: "状态", type: "select", required: true, section: "基本信息", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  {
    key: "country", label: "国家码", required: true, section: "归属市场", placeholder: "AE",
    pattern: { re: "^[A-Za-z]{2}$", msg: "国家码需为 ISO alpha-2（如 AE）" },
  },
  { key: "currency", label: "币种", required: true, maxLength: 3, section: "归属市场", placeholder: "AED" },
  { key: "swiftPrefix", label: "SWIFT 前缀", required: true, maxLength: 11, section: "账户校验", placeholder: "EBILAEAD" },
  {
    key: "ibanLength", label: "IBAN 长度", type: "number", required: true, min: 15, max: 34, section: "账户校验",
    help: "提现收款账户按此位数校验 IBAN（AE 23 / SA 24 / EG 29），填错会导致提现打款失败",
  },
];

// —— §15 问题管理 ——
const PROBLEM_CATEGORY: Record<ProblemEntry["category"], string> = {
  RENT: "借出", RETURN: "归还", BILLING: "计费与退款", DEVICE: "设备故障", ACCOUNT: "账号", OTHER: "其他",
};
const PROBLEM_ACTION: StatusMap<ProblemEntry["suggestedAction"]> = {
  SELF_SERVICE: { label: "自助解决", tone: "muted" },
  TO_WORKORDER: { label: "转工单", tone: "warning" },
  TO_REFUND: { label: "转退款", tone: "danger" },
  TO_CS: { label: "转人工客服", tone: "outline" },
};
const PROBLEM_CATEGORY_OPTIONS = (Object.keys(PROBLEM_CATEGORY) as ProblemEntry["category"][])
  .map((k) => ({ value: k, label: PROBLEM_CATEGORY[k] }));
const PROBLEM_FIELDS: FieldDef[] = [
  { key: "problemNo", label: "问题号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "基本信息" },
  { key: "category", label: "分类", type: "select", required: true, section: "基本信息", options: PROBLEM_CATEGORY_OPTIONS },
  { key: "sortNo", label: "排序", type: "number", required: true, min: 1, section: "基本信息", help: "数字小的排前面，决定 C 端报障下拉的顺序" },
  { key: "status", label: "状态", type: "select", required: true, section: "基本信息", options: [{ value: "ENABLED", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  // 三语标题 + 三语答复共 6 个输入：C 端报障下拉直接取标题，客服快捷答复直接取答复
  { key: "title", label: "标题（中文）", required: true, maxLength: 40, section: "三语内容", placeholder: "扫码后充电宝没弹出" },
  { key: "titleEn", label: "标题（English）", maxLength: 80, section: "三语内容", placeholder: "Nothing ejected after scanning" },
  { key: "titleAr", label: "标题（العربية）", maxLength: 80, section: "三语内容", placeholder: "لم تخرج البطارية بعد مسح الرمز" },
  { key: "answer", label: "标准答复（中文）", type: "textarea", rows: 3, required: true, maxLength: 300, section: "三语内容" },
  { key: "answerEn", label: "标准答复（English）", type: "textarea", rows: 3, maxLength: 400, section: "三语内容" },
  { key: "answerAr", label: "标准答复（العربية）", type: "textarea", rows: 3, maxLength: 400, section: "三语内容" },
  {
    key: "suggestedAction", label: "建议处置", type: "select", required: true, section: "处置",
    options: [
      { value: "SELF_SERVICE", label: "自助解决" }, { value: "TO_WORKORDER", label: "转工单" },
      { value: "TO_REFUND", label: "转退款" }, { value: "TO_CS", label: "转人工客服" },
    ],
    help: "C 端报障与客服受理据此决定下一步——比竞品纯 FAQ 多的就是这一列",
  },
];

// —— §16 税率与发票 ——
const TAX_FIELDS: FieldDef[] = [
  {
    key: "country", label: "国家码", required: true, readOnlyOnEdit: true, section: "适用范围", placeholder: "AE",
    pattern: { re: "^[A-Za-z]{2}$", msg: "国家码需为 ISO alpha-2（如 AE）" },
    help: "业务键，自动转大写；建档后不可改",
  },
  { key: "countryName", label: "国家名称", required: true, maxLength: 30, section: "适用范围", placeholder: "阿联酋" },
  { key: "taxName", label: "税种名称", required: true, maxLength: 30, section: "税率", placeholder: "VAT" },
  { key: "ratePercent", label: "税率（%）", type: "number", required: true, min: 0, max: 100, section: "税率" },
  { key: "includedInPrice", label: "价内税", type: "switch", section: "税率", help: "开启 = 价内税（展示价已含税）；关闭 = 价外税（结算时另加）——直接影响 C 端价格展示" },
  { key: "trn", label: "税号（TRN）", required: true, maxLength: 20, section: "开票信息", placeholder: "1000****00003", help: "前端仅存掩码占位，完整税号由后端保管" },
  { key: "invoiceTitle", label: "默认开票抬头", required: true, maxLength: 60, section: "开票信息", placeholder: "ShareHub FZ-LLC" },
  { key: "effectiveFrom", label: "生效日期", type: "date", required: true, section: "开票信息" },
];

/** ✓/✗ 图标组：登录设置列表用它一眼看清"哪个国家开了什么"（比 是/否 文字更快扫读）。 */
function OnOff({ on, label }: { on: boolean; label: string }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs" title={`${label}：${on ? "开启" : "关闭"}`}>
      {on ? <Check className="size-3.5 text-[var(--success)]" /> : <X className="size-3.5 text-muted-foreground" />}
      <span className={on ? undefined : "text-muted-foreground"}>{label}</span>
    </span>
  );
}

/** 灰度比例条：复用共享 Progress（showText 关掉，因为这里是"百分比"而非"已用/总数"）。 */
function RolloutBar({ percent }: { percent: number }) {
  const v = Math.max(0, Math.min(100, percent));
  return (
    <div className="flex items-center gap-2">
      <Progress value={v} total={100} showText={false} className="min-w-0 w-20" />
      <span className="tabular-nums text-xs text-muted-foreground">{v}%</span>
    </div>
  );
}

/** 带单位后缀的数值输入（业务规则页专用；FormDrawer 做不了"多块独立保存"，故就地组表单）。 */
function NumField({
  label, unit, value, onChange, help, disabled,
}: { label: string; unit: string; value: number; onChange: (v: number) => void; help?: string; disabled?: boolean }) {
  return (
    <div className="mb-4">
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className="flex items-center gap-2">
        <Input
          type="number"
          className="w-40"
          value={Number.isFinite(value) ? value : 0}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === "" ? 0 : Number(e.target.value))}
        />
        <span className="text-xs text-muted-foreground">{unit}</span>
      </div>
      {help && <div className="mt-1 text-xs text-muted-foreground/70">{help}</div>}
    </div>
  );
}

/**
 * §11 业务规则：**不是列表页**——竞品拆「提现设置 / 预约设置 / 充电设置」三个菜单，
 * 我们合并为一页三张 Card，各自保存（规格 §11 / 方案 §3.3 分区表单）。
 * 校验：数值非负；手续费率 0~1；提交前拦截并 notify.error（分区表单没有 FormDrawer 的字段级校验）。
 */
function BizRulesPanel({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const { t } = useI18n();
  const q = useQuery({ queryKey: ["sys", "biz-rules"], queryFn: () => api.getBizRules() });
  const [withdraw, setWithdraw] = useState<WithdrawRule | null>(null);
  const [reservation, setReservation] = useState<ReservationRule | null>(null);
  const [billing, setBilling] = useState<BillingDefaultRule | null>(null);
  useEffect(() => {
    if (!q.data) return;
    setWithdraw(q.data.withdraw); setReservation(q.data.reservation); setBilling(q.data.billing);
  }, [q.data]);

  const save = useMutation({
    mutationFn: (x: Parameters<typeof api.saveBizRules>[0]) => api.saveBizRules(x),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["sys", "biz-rules"] }); notify.success(t("common.success")); },
  });
  const currency = q.data?.currency ?? "AED";
  const nonNegative = (...vals: number[]) => vals.every((v) => Number.isFinite(v) && v >= 0);

  if (q.isLoading || !withdraw || !reservation || !billing) {
    return <Card className="p-5 text-sm text-muted-foreground">加载业务规则…</Card>;
  }

  const SaveBar = ({ onSave }: { onSave: () => void }) => (
    <div className="mt-1 flex justify-end">
      <Button size="sm" disabled={!canEdit || save.isPending} onClick={onSave}>{t("common.save")}</Button>
    </div>
  );

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Card className="p-5">
        <div className="mb-1 text-sm font-medium">提现规则</div>
        <div className="mb-4 text-xs text-muted-foreground">
          提现审核页的手续费口径以此为唯一来源，勿在别处硬编码。
        </div>
        <NumField label="最低提现额" unit={currency} value={withdraw.minAmount} disabled={!canEdit} onChange={(v) => setWithdraw({ ...withdraw, minAmount: v })} />
        <NumField label="手续费率" unit="0~1（0.006 = 0.6%）" value={withdraw.feeRate} disabled={!canEdit} onChange={(v) => setWithdraw({ ...withdraw, feeRate: v })} />
        <NumField label="手续费封顶" unit={currency} value={withdraw.feeCap} disabled={!canEdit} onChange={(v) => setWithdraw({ ...withdraw, feeCap: v })} />
        <NumField label="结算周期" unit="T+N 天" value={withdraw.settleDays} disabled={!canEdit} onChange={(v) => setWithdraw({ ...withdraw, settleDays: v })} />
        <NumField label="单日限额" unit={currency} value={withdraw.dailyLimit} disabled={!canEdit} onChange={(v) => setWithdraw({ ...withdraw, dailyLimit: v })} />
        <Field label="需人工审批">
          <Select className="w-40" value={withdraw.needApproval ? "1" : "0"} disabled={!canEdit} onChange={(e) => setWithdraw({ ...withdraw, needApproval: e.target.value === "1" })}>
            <option value="1">是（走提现审核队列）</option>
            <option value="0">否（自动放款）</option>
          </Select>
        </Field>
        <SaveBar onSave={() => {
          if (!nonNegative(withdraw.minAmount, withdraw.feeCap, withdraw.settleDays, withdraw.dailyLimit)) { notify.error("提现规则：金额与周期不能为负数"); return; }
          if (!(withdraw.feeRate >= 0 && withdraw.feeRate <= 1)) { notify.error("手续费率需在 0~1 之间（0.006 = 0.6%）"); return; }
          save.mutate({ withdraw });
        }} />
      </Card>

      <Card className="p-5">
        <div className="mb-1 text-sm font-medium">预约规则</div>
        <div className="mb-4 text-xs text-muted-foreground">
          热门点位高峰占位：预约取宝 / 预约还位共用这套阈值。
        </div>
        <NumField label="预约时长上限" unit="分钟" value={reservation.maxDurationMin} disabled={!canEdit} onChange={(v) => setReservation({ ...reservation, maxDurationMin: v })} />
        <NumField label="提前预约上限" unit="小时" value={reservation.advanceHours} disabled={!canEdit} onChange={(v) => setReservation({ ...reservation, advanceHours: v })} />
        <NumField label="超时未取占位费" unit={`${currency} / 分钟`} value={reservation.holdFeePerMin} disabled={!canEdit} onChange={(v) => setReservation({ ...reservation, holdFeePerMin: v })} help="预约订单页的占位费按此计算" />
        <NumField label="单用户同时预约上限" unit="笔" value={reservation.maxConcurrent} disabled={!canEdit} onChange={(v) => setReservation({ ...reservation, maxConcurrent: v })} />
        <SaveBar onSave={() => {
          if (!nonNegative(reservation.maxDurationMin, reservation.advanceHours, reservation.holdFeePerMin, reservation.maxConcurrent)) { notify.error("预约规则：数值不能为负数"); return; }
          save.mutate({ reservation });
        }} />
      </Card>

      <Card className="p-5">
        <div className="mb-1 text-sm font-medium">计费默认值</div>
        <div className="mb-4 text-xs text-muted-foreground">
          新建价格方案时的初始值；已存在的方案不受影响。
        </div>
        <NumField label="默认免费时长" unit="分钟" value={billing.freeMinutes} disabled={!canEdit} onChange={(v) => setBilling({ ...billing, freeMinutes: v })} />
        <NumField label="默认计费单位" unit="分钟 / 计费周期" value={billing.unitMinutes} disabled={!canEdit} onChange={(v) => setBilling({ ...billing, unitMinutes: v })} />
        <NumField label="默认日封顶" unit={currency} value={billing.capDaily} disabled={!canEdit} onChange={(v) => setBilling({ ...billing, capDaily: v })} />
        <NumField label="默认买断价" unit={currency} value={billing.buyoutPrice} disabled={!canEdit} onChange={(v) => setBilling({ ...billing, buyoutPrice: v })} />
        <NumField label="超时判定阈值" unit="小时" value={billing.overdueHours} disabled={!canEdit} onChange={(v) => setBilling({ ...billing, overdueHours: v })} help="超过即判定逾期并触发买断" />
        <SaveBar onSave={() => {
          if (!nonNegative(billing.freeMinutes, billing.unitMinutes, billing.capDaily, billing.buyoutPrice, billing.overdueHours)) { notify.error("计费默认值：数值不能为负数"); return; }
          if (billing.unitMinutes <= 0) { notify.error("计费单位必须大于 0 分钟"); return; }
          save.mutate({ billing });
        }} />
      </Card>
    </div>
  );
}

function SystemInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "vendors");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  // —— 供应商接入（Vendor[]，非分页；含配置抽屉，保留）——
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors(), enabled: tab === "vendors" });
  const [edit, setEdit] = useState<Vendor | null>(null);
  const [form, setForm] = useState<Partial<Vendor>>({});
  const save = useMutation({
    mutationFn: (v: Partial<Vendor> & { vendorCode: string }) => api.saveVendor(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["vendors"] }); setEdit(null); },
  });
  function openVendor(v: Vendor) { setEdit(v); setForm(v); }

  // —— 编辑门控与表单 state ——
  const canNotify = allow("system:notify_template:update");
  const canDict = allow("system:dict:update");
  const canRegion = allow("system:region:update");
  const canParam = allow("system:param:update");
  const canOpenapi = allow("system:openapi:update");
  const canPayment = allow("system:payment_channel:update");
  const canMarket = allow("system:market:update");

  const [notifyForm, setNotifyForm] = useState<Partial<NotifyTemplate> | null>(null);
  const [dictForm, setDictForm] = useState<Partial<DictEntry> | null>(null);
  const [regionForm, setRegionForm] = useState<Partial<Region> | null>(null);
  const [paramForm, setParamForm] = useState<Partial<SysParam> | null>(null);
  const [openapiForm, setOpenapiForm] = useState<Partial<OpenApiApp> | null>(null);
  const [paymentForm, setPaymentForm] = useState<Partial<PaymentChannel> | null>(null);
  const [marketForm, setMarketForm] = useState<Partial<MarketCountry> | null>(null);

  // —— B2/B3/B5 八个 tab：写权限门控（权限码见 功能权限清单 §13）——
  const canBlacklist = allow("system:notify_blacklist:update");
  const canBizRule = allow("system:biz_rule:update");
  const canLogin = allow("system:login_setting:update");
  const canAppVersion = allow("system:app_version:release");
  const canBank = allow("system:bank:update");
  const canProblem = allow("system:problem:update");
  const canTax = allow("system:tax:update");

  // 各 tab 的筛选器（切 tab 时统一清空，见 TabHeader onChange）
  const [logChannel, setLogChannel] = useState("");
  const [logStatus, setLogStatus] = useState("");
  const [logSort, setLogSort] = useState<{ key: string; dir: SortDir }>({ key: "sentAt", dir: "desc" });
  const [blChannel, setBlChannel] = useState("");
  const [blReason, setBlReason] = useState("");
  const [verPlatform, setVerPlatform] = useState("");
  const [bankCountry, setBankCountry] = useState("");
  const [bankCurrency, setBankCurrency] = useState("");
  const [probCategory, setProbCategory] = useState("");
  const [probStatus, setProbStatus] = useState("");
  // G1 软删除：默认过滤已归档；开关打开才把归档行拉回来（banks / problems 共用一个 state，切 tab 时清空）
  const [showArchived, setShowArchived] = useState(false);

  const [blacklistForm, setBlacklistForm] = useState<Partial<NotifyBlacklist> | null>(null);
  const [loginForm, setLoginForm] = useState<Partial<LoginSetting> | null>(null);
  const [versionForm, setVersionForm] = useState<Partial<AppVersion> | null>(null);
  const [bankForm, setBankForm] = useState<Partial<BankEntry> | null>(null);
  const [problemForm, setProblemForm] = useState<Partial<ProblemEntry> | null>(null);
  const [taxForm, setTaxForm] = useState<Partial<TaxSetting> | null>(null);
  // 失败详情抽屉：失败行点「详情」看原始报错（列表里放不下完整报错）
  const [logDetail, setLogDetail] = useState<NotifyLog | null>(null);
  const { confirm, dialog } = useConfirm();

  const onSaved = (setter: (v: null) => void) => () => { qc.invalidateQueries({ queryKey: ["sys"] }); notify.success(t("common.success")); setter(null); };
  const saveNotify = useMutation({ mutationFn: (v: Partial<NotifyTemplate>) => api.saveNotifyTemplate(v), onSuccess: onSaved(setNotifyForm) });
  const saveDict = useMutation({ mutationFn: (v: Partial<DictEntry>) => api.saveDictEntry(v), onSuccess: onSaved(setDictForm) });
  const saveRegion = useMutation({ mutationFn: (v: Partial<Region>) => api.saveRegion(v), onSuccess: onSaved(setRegionForm) });
  const saveParam = useMutation({ mutationFn: (v: Partial<SysParam>) => api.saveSysParam(v), onSuccess: onSaved(setParamForm) });
  const saveOpenapi = useMutation({ mutationFn: (v: Partial<OpenApiApp>) => api.saveOpenApiApp(v), onSuccess: onSaved(setOpenapiForm) });
  const savePayment = useMutation({ mutationFn: (v: Partial<PaymentChannel>) => api.savePaymentChannel(v), onSuccess: onSaved(setPaymentForm) });
  const saveMarket = useMutation({ mutationFn: (v: Partial<MarketCountry>) => api.saveMarketCountry(v), onSuccess: onSaved(setMarketForm) });
  const saveBlacklist = useMutation({ mutationFn: (v: Partial<NotifyBlacklist>) => api.saveNotifyBlacklist(v), onSuccess: onSaved(setBlacklistForm) });
  const saveLogin = useMutation({ mutationFn: (v: Partial<LoginSetting>) => api.saveLoginSetting(v), onSuccess: onSaved(setLoginForm) });
  const saveVersion = useMutation({ mutationFn: (v: Partial<AppVersion>) => api.saveAppVersion(v), onSuccess: onSaved(setVersionForm) });
  const saveBank = useMutation({ mutationFn: (v: Partial<BankEntry>) => api.saveBank(v), onSuccess: onSaved(setBankForm) });
  const saveProblem = useMutation({ mutationFn: (v: Partial<ProblemEntry>) => api.saveProblem(v), onSuccess: onSaved(setProblemForm) });
  const saveTax = useMutation({ mutationFn: (v: Partial<TaxSetting>) => api.saveTaxSetting(v), onSuccess: onSaved(setTaxForm) });
  const releaseBlock = useMutation({
    mutationFn: (blockNo: string) => api.releaseNotifyBlacklist(blockNo),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ["sys"] }); notify.success(`已解除拉黑 ${r.target}`); },
  });
  // G1 软删除（TDD §10.1）：只有归档/恢复，没有物理删除。错误由全局 MutationCache 接管，此处不 catch。
  const invalidateSys = () => { qc.invalidateQueries({ queryKey: ["sys"] }); };
  const archiveBank = useMutation({ mutationFn: (code: string) => api.archiveBank(code), onSuccess: () => { invalidateSys(); notify.success("已归档"); } });
  const unarchiveBank = useMutation({ mutationFn: (code: string) => api.unarchiveBank(code), onSuccess: () => { invalidateSys(); notify.success("已恢复"); } });
  const archiveProblem = useMutation({ mutationFn: (no: string) => api.archiveProblem(no), onSuccess: () => { invalidateSys(); notify.success("已归档"); } });
  const unarchiveProblem = useMutation({ mutationFn: (no: string) => api.unarchiveProblem(no), onSuccess: () => { invalidateSys(); notify.success("已恢复"); } });

  const rollbackVersion = useMutation({
    mutationFn: (versionId: string) => api.rollbackAppVersion(versionId),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ["sys"] }); notify.success(`已回滚 ${PLATFORM_LABEL[r.platform]} ${r.versionNo}，灰度已归零`); },
  });

  // —— 其余分页 tab ——
  // 筛选值一并进 queryKey：否则改筛选器不会重新取数。
  const filterKey = [logChannel, logStatus, logSort.key, logSort.dir, blChannel, blReason, verPlatform, bankCountry, bankCurrency, probCategory, probStatus, showArchived ? "arc" : ""].join("|");
  const q = useQuery<PageResult<NotifyTemplate | DictEntry | Region | SysParam | OpenApiApp | MarketCountry | PaymentChannel | NotifyLog | NotifyBlacklist | LoginSetting | AppVersion | BankEntry | ProblemEntry | TaxSetting>>({
    queryKey: ["sys", tab, page, keyword, filterKey],
    queryFn: () =>
      tab === "payment" ? api.listPaymentChannels({ page, size: SIZE, keyword })
      : tab === "notify" ? api.listNotifyTemplates({ page, size: SIZE, keyword })
      : tab === "notify-log" ? api.listNotifyLogs({ page, size: SIZE, keyword, channel: logChannel, status: logStatus, sort: logSort.key, dir: logSort.dir })
      : tab === "notify-blacklist" ? api.listNotifyBlacklist({ page, size: SIZE, keyword, channel: blChannel, reason: blReason })
      : tab === "login" ? api.listLoginSettings({ page, size: SIZE, keyword })
      : tab === "app-version" ? api.listAppVersions({ page, size: SIZE, keyword, platform: verPlatform })
      : tab === "banks" ? api.listBanks({ page, size: SIZE, keyword, country: bankCountry, currency: bankCurrency, showArchived })
      : tab === "problems" ? api.listProblems({ page, size: SIZE, keyword, category: probCategory, status: probStatus, showArchived })
      : tab === "tax" ? api.listTaxSettings({ page, size: SIZE, keyword })
      : tab === "dict" ? api.listDictEntries({ page, size: SIZE, keyword })
      : tab === "region" ? api.listRegions({ page, size: SIZE, keyword })
      : tab === "params" ? api.listSysParams({ page, size: SIZE, keyword })
      : tab === "markets" ? api.listMarketCountries({ page, size: SIZE, keyword })
      : api.listOpenApiApps({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab !== "vendors" && tab !== "rules",
  });
  // 发送记录页头统计：全量口径（今日发送量 / 失败率 / 今日成本），与当页数据无关。
  const logStats = useQuery({ queryKey: ["sys", "notify-log-stats"], queryFn: () => api.getNotifyLogStats(), enabled: tab === "notify-log" });

  const MARKET_STATUS: StatusMap<MarketCountry["status"]> = {
    LIVE: { label: "已开城", tone: "success" },
    PILOT: { label: "试点", tone: "outline" },
    PLANNED: { label: "规划", tone: "muted" },
  };
  const marketCols: Column<MarketCountry>[] = [
    { header: "国家", cell: (m) => <span className="font-medium">{m.name}（{m.countryCode}）</span> },
    { header: "币种", cell: (m) => <Badge tone="outline">{m.currency}</Badge> },
    { header: "时区", cell: (m) => <span className="text-muted-foreground">{m.timezone}</span> },
    { header: "合规主体", cell: (m) => <span className="text-muted-foreground">{m.compliance}</span> },
    { header: "开城数", cell: (m) => <span className="tabular-nums">{m.cityCount}</span> },
    { header: "状态", cell: (m) => <StatusBadge map={MARKET_STATUS} value={m.status} /> },
    { header: t("common.actions"), cell: (m) => canMarket ? <Button size="sm" variant="outline" onClick={() => setMarketForm(m)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const paymentCols: Column<PaymentChannel>[] = [
    { header: "渠道码", cell: (c) => <span className="font-medium">{c.channelCode}</span> },
    { header: "名称", cell: (c) => c.channelName },
    { header: "模式", cell: (c) => <Badge tone="outline">{c.mode === "DELEGATED" ? "委托" : "直连"}</Badge> },
    { header: "适用国家", cell: (c) => <span className="tabular-nums">{c.countries}</span> },
    { header: "币种", cell: (c) => <span className="tabular-nums">{c.currencies}</span> },
    // 能力矩阵：决定能否走预授权（免押）与分账（场地方/代理商）
    { header: "能力", cell: (c) => <span className="text-muted-foreground">{c.capabilities}</span> },
    { header: "商户号", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.merchantId}</span> },
    { header: "密钥", cell: () => <span className="text-muted-foreground tabular-nums">****</span> },
    { header: "状态", cell: (c) => <EnabledBadge on={c.status === "ENABLED"} /> },
    { header: "更新时间", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.updatedAt)}</span> },
    { header: "操作", cell: (c) => canPayment ? <Button size="sm" variant="outline" onClick={() => setPaymentForm(c)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const editBtn = <T,>(can: boolean, open: (r: T) => void) => (row: T) =>
    can ? <Button size="sm" variant="outline" onClick={() => open(row)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span>;

  const vendorCols: Column<Vendor>[] = [
    { header: "供应商码", cell: (v) => <span className="font-medium">{v.vendorCode}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "接入方式", cell: (v) => <Badge tone="outline">{MODE_LABEL[v.accessMode]}</Badge> },
    { header: "设备数", cell: (v) => <span className="tabular-nums">{v.deviceCount}</span> },
    { header: "状态", cell: (v) => <EnabledBadge on={v.status === "ENABLED"} /> },
    { header: "操作", cell: (v) => allow("device:vendor:config") ? <Button size="sm" variant="outline" onClick={() => openVendor(v)}>配置</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const notifyCols: Column<NotifyTemplate>[] = [
    { header: "模板号", cell: (t) => <span className="font-medium">{t.templateNo}</span> },
    { header: "名称", cell: (t) => t.name },
    { header: "渠道", cell: (t) => <Badge tone="outline">{CHANNEL_LABEL[t.channel]}</Badge> },
    { header: "语言", cell: (t) => <span className="text-muted-foreground">{LANG_LABEL[t.lang]}</span> },
    { header: "状态", cell: (t) => <EnabledBadge on={t.status === "ENABLED"} /> },
    { header: t("common.actions"), cell: editBtn<NotifyTemplate>(canNotify, setNotifyForm) },
  ];

  const dictCols: Column<DictEntry>[] = [
    { header: "字典号", cell: (d) => <span className="font-medium">{d.dictNo}</span> },
    { header: "分组", cell: (d) => <Badge tone="outline">{d.group}</Badge> },
    { header: "编码", cell: (d) => <span className="text-muted-foreground tabular-nums">{d.code}</span> },
    { header: "标签", cell: (d) => d.label },
    { header: "排序", cell: (d) => <span className="tabular-nums">{d.sort}</span> },
    { header: "状态", cell: (d) => <EnabledBadge on={d.enabled} /> },
    { header: t("common.actions"), cell: editBtn<DictEntry>(canDict, setDictForm) },
  ];

  const regionCols: Column<Region>[] = [
    { header: "区域 ID", cell: (r) => <span className="font-medium">{r.regionId}</span> },
    { header: "名称", cell: (r) => r.name },
    { header: "上级", cell: (r) => <span className="text-muted-foreground">{r.parent || "-"}</span> },
    { header: "层级", cell: (r) => <span className="tabular-nums">{r.level}</span> },
    { header: "城市数", cell: (r) => <span className="tabular-nums">{r.cityCount}</span> },
    { header: t("common.actions"), cell: editBtn<Region>(canRegion, setRegionForm) },
  ];

  const paramCols: Column<SysParam>[] = [
    { header: "参数键", cell: (p) => <span className="font-medium">{p.paramKey}</span> },
    { header: "说明", cell: (p) => p.label },
    { header: "取值", cell: (p) => <span className="tabular-nums">{p.value}</span> },
    { header: "分组", cell: (p) => <Badge tone="outline">{p.groupName}</Badge> },
    { header: "更新时间", cell: (p) => <span className="text-muted-foreground">{fmtTime(p.updatedAt)}</span> },
    { header: t("common.actions"), cell: editBtn<SysParam>(canParam, setParamForm) },
  ];

  const openapiCols: Column<OpenApiApp>[] = [
    { header: "应用号", cell: (a) => <span className="font-medium">{a.appNo}</span> },
    { header: "名称", cell: (a) => a.name },
    { header: "AppKey", cell: (a) => <span className="text-muted-foreground tabular-nums">{a.appKey}</span> },
    { header: "限流（次/秒）", cell: (a) => <span className="tabular-nums">{a.rateLimit}</span> },
    { header: "状态", cell: (a) => <EnabledBadge on={a.status === "ACTIVE"} /> },
    { header: "创建时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    { header: t("common.actions"), cell: editBtn<OpenApiApp>(canOpenapi, setOpenapiForm) },
  ];

  // —— §9 发送记录：全渠道 + 计费，目标脱敏，时间/成本可排序 ——
  const logCols: Column<NotifyLog>[] = [
    { header: "流水号", cell: (l) => <span className="font-medium tabular-nums">{l.logNo}</span> },
    { header: "渠道", cell: (l) => <Badge tone="outline">{LOG_CHANNEL[l.channel]}</Badge> },
    { header: "模板", cell: (l) => <span className="text-muted-foreground tabular-nums">{l.templateNo}</span> },
    // 目标脱敏：运营端排障只需要看得出"发给谁"，不需要完整联系方式
    { header: "目标（脱敏）", cell: (l) => <span className="tabular-nums">{l.target}</span> },
    { header: "场景", cell: (l) => <span className="text-muted-foreground">{l.scene}</span> },
    { header: "发送时间", sortKey: "sentAt", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.sentAt)}</span> },
    { header: "状态", cell: (l) => <Badge tone={l.status === "SENT" ? "success" : "danger"}>{l.status === "SENT" ? "已发送" : "发送失败"}</Badge> },
    { header: "成本", sortKey: "cost", cell: (l) => <span className="tabular-nums">{money(l.cost, l.currency)}</span> },
    {
      header: t("common.actions"),
      cell: (l) => l.status === "FAILED"
        ? <Button size="sm" variant="outline" onClick={() => setLogDetail(l)}>失败原因</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  // —— §10 触达拉黑：到期时间在过去 = 已解除（软删除保留审计痕迹）——
  const isReleased = (b: NotifyBlacklist) => !!b.expireAt && new Date(b.expireAt).getTime() <= Date.now();
  const blacklistCols: Column<NotifyBlacklist>[] = [
    { header: "拉黑号", cell: (b) => <span className="font-medium tabular-nums">{b.blockNo}</span> },
    { header: "目标", cell: (b) => <span className="tabular-nums">{b.target}</span> },
    { header: "渠道", cell: (b) => <Badge tone={b.channel === "ALL" ? "danger" : "outline"}>{BL_CHANNEL[b.channel]}</Badge> },
    { header: "原因", cell: (b) => <StatusBadge map={BL_REASON} value={b.reason} /> },
    { header: "拉黑时间", cell: (b) => <span className="text-muted-foreground">{fmtTime(b.blockedAt)}</span> },
    { header: "操作人", cell: (b) => <span className="text-muted-foreground">{b.blockedBy}</span> },
    { header: "到期时间", cell: (b) => b.expireAt ? <span className="text-muted-foreground">{fmtTime(b.expireAt)}</span> : <Badge tone="warning">永久</Badge> },
    { header: "状态", cell: (b) => isReleased(b) ? <Badge tone="muted">已解除</Badge> : <Badge tone="danger">拉黑中</Badge> },
    {
      header: t("common.actions"),
      cell: (b) => !canBlacklist ? <span className="text-muted-foreground">-</span>
        : isReleased(b) ? <span className="text-muted-foreground">已解除</span>
        : (
          <Button
            size="sm"
            variant="outline"
            disabled={releaseBlock.isPending}
            onClick={async () => {
              const ok = await confirm({
                title: "解除拉黑",
                desc: `解除后 ${b.target} 将重新接收${BL_CHANNEL[b.channel]}触达。记录会保留（软删除），到期时间置为当下。`,
                confirmText: "解除",
              });
              if (ok) releaseBlock.mutate(b.blockNo);
            }}
          >
            解除
          </Button>
        ),
    },
  ];

  // —— §12 登录设置：按国家分行，开关列用 ✓/✗ 图标组 ——
  const loginCols: Column<LoginSetting>[] = [
    {
      header: "国家",
      cell: (s) => s.country === "*"
        ? <span className="font-medium">默认档 <Badge tone="default">*</Badge></span>
        : <span className="font-medium">{s.countryName}（{s.country}）</span>,
    },
    {
      header: "登录方式",
      cell: (s) => (
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <OnOff on={s.otpEnabled} label="验证码" />
          <OnOff on={s.passwordEnabled} label="密码" />
        </div>
      ),
    },
    {
      header: "第三方登录",
      cell: (s) => (
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          <OnOff on={s.appleEnabled} label="Apple" />
          <OnOff on={s.googleEnabled} label="Google" />
        </div>
      ),
    },
    { header: "OTP 有效期", cell: (s) => s.otpEnabled ? <span className="tabular-nums">{s.otpExpireSec} 秒</span> : <span className="text-muted-foreground">-</span> },
    { header: "日发送上限", cell: (s) => s.otpEnabled ? <span className="tabular-nums">{s.otpDailyLimit} 条/人</span> : <span className="text-muted-foreground">-</span> },
    { header: "强制实名", cell: (s) => s.forceRealName ? <Badge tone="warning">强制</Badge> : <Badge tone="muted">不强制</Badge> },
    { header: t("common.actions"), cell: editBtn<LoginSetting>(canLogin, setLoginForm) },
  ];

  // —— §13 应用版本：按平台分组（列表已按 平台→构建号 排序）+ 灰度进度条 + 强更 danger ——
  const versionCols: Column<AppVersion>[] = [
    { header: "平台", cell: (v) => <Badge tone="outline">{PLATFORM_LABEL[v.platform]}</Badge> },
    { header: "版本号", cell: (v) => <span className="font-medium tabular-nums">{v.versionNo}</span> },
    { header: "构建号", cell: (v) => <span className="tabular-nums text-muted-foreground">{v.buildNo}</span> },
    { header: "更新说明（中）", cell: (v) => <span className="text-muted-foreground">{v.releaseNote}</span> },
    { header: "强更", cell: (v) => v.forceUpdate ? <Badge tone="danger">强制更新</Badge> : <span className="text-muted-foreground">-</span> },
    { header: "最低支持", cell: (v) => <span className="tabular-nums text-muted-foreground">{v.minSupported || "-"}</span> },
    { header: "灰度", cell: (v) => <RolloutBar percent={v.rolloutPercent} /> },
    { header: "状态", cell: (v) => <StatusBadge map={VERSION_STATUS} value={v.status} /> },
    { header: "发布时间", cell: (v) => <span className="text-muted-foreground">{fmtTime(v.releasedAt)}</span> },
    {
      header: t("common.actions"),
      cell: (v) => !canAppVersion ? <span className="text-muted-foreground">-</span> : (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setVersionForm(v)}>{t("common.edit")}</Button>
          {v.status === "RELEASED" && (
            <Button
              size="sm"
              variant="outline"
              disabled={rollbackVersion.isPending}
              onClick={async () => {
                const ok = await confirm({
                  title: `回滚 ${PLATFORM_LABEL[v.platform]} ${v.versionNo}`,
                  desc: "回滚会立即把灰度比例归零并停止下发；已升级的用户不会自动降级。请确认新版本确有阻断性问题。",
                  danger: true,
                  confirmText: "确认回滚",
                  requireText: v.versionNo,
                });
                if (ok) rollbackVersion.mutate(v.versionId);
              }}
            >
              回滚
            </Button>
          )}
        </div>
      ),
    },
  ];

  // —— §14 银行管理 ——
  const bankCols: Column<BankEntry>[] = [
    { header: "银行代码", cell: (b) => <span className="font-medium tabular-nums">{b.bankCode}</span> },
    { header: "银行名称", cell: (b) => <>{b.bankName} <span className="text-muted-foreground">· {b.bankNameEn}</span></> },
    { header: "国家", cell: (b) => <Badge tone="outline">{b.country}</Badge> },
    { header: "币种", cell: (b) => <span className="tabular-nums">{b.currency}</span> },
    { header: "SWIFT 前缀", cell: (b) => <span className="tabular-nums text-muted-foreground">{b.swiftPrefix}</span> },
    // IBAN 长度：提现收款账户校验直接读这里，填错会让提现打款失败
    { header: "IBAN 长度", cell: (b) => <span className="tabular-nums">{b.ibanLength} 位</span> },
    { header: "状态", cell: (b) => <EnabledBadge on={b.status === "ENABLED"} /> },
    // 归档时间仅在「显示已归档」打开时占列，平时不白占一列
    ...(showArchived ? [{ header: "归档时间", cell: (b: BankEntry) => <ArchivedAt at={b.archivedAt} /> }] : []),
    {
      header: t("common.actions"),
      cell: (b) => (
        <ArchiveActions
          archived={!!b.archivedAt}
          canWrite={canBank}
          actions={<Button size="sm" variant="outline" onClick={() => setBankForm(b)}>{t("common.edit")}</Button>}
          onArchive={async () => {
            // 银行不在「主数据强确认」清单里，不要求手输编号
            if (await confirm(archiveConfirm("银行", `${b.bankName}（${b.bankCode}）`))) archiveBank.mutate(b.bankCode);
          }}
          onUnarchive={async () => {
            if (await confirm(unarchiveConfirm("银行", `${b.bankName}（${b.bankCode}）`))) unarchiveBank.mutate(b.bankCode);
          }}
        />
      ),
    },
  ];

  // —— §15 问题管理 ——
  const problemCols: Column<ProblemEntry>[] = [
    { header: "问题号", cell: (p) => <span className="font-medium tabular-nums">{p.problemNo}</span> },
    { header: "分类", cell: (p) => <Badge tone="outline">{PROBLEM_CATEGORY[p.category]}</Badge> },
    { header: "标题（中）", cell: (p) => p.title },
    { header: "建议处置", cell: (p) => <StatusBadge map={PROBLEM_ACTION} value={p.suggestedAction} /> },
    { header: "排序", cell: (p) => <span className="tabular-nums">{p.sortNo}</span> },
    { header: "状态", cell: (p) => <EnabledBadge on={p.status === "ENABLED"} /> },
    ...(showArchived ? [{ header: "归档时间", cell: (p: ProblemEntry) => <ArchivedAt at={p.archivedAt} /> }] : []),
    {
      header: t("common.actions"),
      cell: (p) => (
        <ArchiveActions
          archived={!!p.archivedAt}
          canWrite={canProblem}
          actions={<Button size="sm" variant="outline" onClick={() => setProblemForm(p)}>{t("common.edit")}</Button>}
          onArchive={async () => {
            if (await confirm(archiveConfirm("问题", `${p.title}（${p.problemNo}）`))) archiveProblem.mutate(p.problemNo);
          }}
          onUnarchive={async () => {
            if (await confirm(unarchiveConfirm("问题", `${p.title}（${p.problemNo}）`))) unarchiveProblem.mutate(p.problemNo);
          }}
        />
      ),
    },
  ];

  // —— §16 税率与发票 ——
  const taxCols: Column<TaxSetting>[] = [
    { header: "国家", cell: (x) => <span className="font-medium">{x.countryName}（{x.country}）</span> },
    { header: "税种", cell: (x) => <Badge tone="outline">{x.taxName}</Badge> },
    { header: "税率", cell: (x) => <span className="tabular-nums">{x.ratePercent}%</span> },
    // 价内/价外直接决定 C 端展示价含不含税，必须醒目
    { header: "计税方式", cell: (x) => x.includedInPrice ? <Badge tone="default">价内税</Badge> : <Badge tone="warning">价外税</Badge> },
    { header: "税号（TRN）", cell: (x) => <span className="tabular-nums text-muted-foreground">{x.trn}</span> },
    { header: "开票抬头", cell: (x) => <span className="text-muted-foreground">{x.invoiceTitle}</span> },
    { header: "生效日期", cell: (x) => <span className="tabular-nums text-muted-foreground">{x.effectiveFrom}</span> },
    { header: t("common.actions"), cell: editBtn<TaxSetting>(canTax, setTaxForm) },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => {
        setTab(k); setPage(1); setKeyword("");
        setLogChannel(""); setLogStatus(""); setLogSort({ key: "sentAt", dir: "desc" });
        setBlChannel(""); setBlReason(""); setVerPlatform("");
        setBankCountry(""); setBankCurrency(""); setProbCategory(""); setProbStatus("");
        setShowArchived(false);
      }} />

      {/* §9 发送记录页头统计：今日发送量 / 失败率 / 今日成本 —— OTP 是真金白银，成本要天天看见 */}
      {tab === "notify-log" && logStats.data && (
        <div className="mb-4 grid gap-4 sm:grid-cols-3">
          <StatCard label="今日发送量" value={logStats.data.sentToday} sub={`全渠道合计 · ${LOG_SCENE_HINT}`} />
          <StatCard
            label="今日失败率"
            value={`${(logStats.data.failRate * 100).toFixed(1)}%`}
            sub={`失败 ${logStats.data.failedToday} 条`}
            tone={logStats.data.failRate > 0.05 ? "down" : "up"}
          />
          <StatCard label="今日成本" value={money(logStats.data.costToday, logStats.data.currency)} sub="短信/WhatsApp 计费，Push 免费" />
        </div>
      )}

      {/* 供应商接入：非分页列表，只给导出（配置走行内抽屉，无搜索/新增）*/}
      {tab === "vendors" && (
        <Toolbar
          onExport={() => exportCsv<Vendor>("供应商接入", [
            { header: "供应商码", value: (v) => v.vendorCode },
            { header: "名称", value: (v) => v.name },
            { header: "接入方式", value: (v) => MODE_LABEL[v.accessMode] },
            { header: "设备数", value: (v) => v.deviceCount },
            { header: "状态", value: (v) => (v.status === "ENABLED" ? "启用" : "停用") },
          ], vendorsQ.data ?? [])}
        />
      )}
      {tab === "payment" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索渠道码 / 名称 / 国家 / 币种"
          // 密钥列导出为掩码占位，与表格一致——CSV 落到本地更不能带真实密钥
          onExport={() => exportCsv<PaymentChannel>("支付渠道", [
            { header: "渠道码", value: (c) => c.channelCode },
            { header: "名称", value: (c) => c.channelName },
            { header: "模式", value: (c) => (c.mode === "DELEGATED" ? "委托" : "直连") },
            { header: "适用国家", value: (c) => c.countries },
            { header: "币种", value: (c) => c.currencies },
            { header: "能力", value: (c) => c.capabilities },
            { header: "商户号", value: (c) => c.merchantId },
            { header: "密钥", value: () => "****" },
            { header: "状态", value: (c) => (c.status === "ENABLED" ? "启用" : "停用") },
            { header: "更新时间", value: (c) => fmtTime(c.updatedAt) },
          ], (q.data?.list ?? []) as PaymentChannel[])}
          onAdd={canPayment ? () => setPaymentForm({ mode: "DIRECT", status: "DISABLED", countries: "AE", currencies: "AED", capabilities: "支付,退款", apiBase: "", merchantId: "", apiKeyMasked: "sk_test_****" }) : undefined} addLabel="新增支付渠道" />
      )}
      {tab === "notify" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索模板号 / 名称"
          onExport={() => exportCsv<NotifyTemplate>("通知模板", [
            { header: "模板号", value: (x) => x.templateNo },
            { header: "名称", value: (x) => x.name },
            { header: "渠道", value: (x) => CHANNEL_LABEL[x.channel] },
            { header: "语言", value: (x) => LANG_LABEL[x.lang] },
            { header: "状态", value: (x) => (x.status === "ENABLED" ? "启用" : "停用") },
          ], (q.data?.list ?? []) as NotifyTemplate[])}
          onAdd={canNotify ? () => setNotifyForm({ channel: "SMS", lang: "ar", status: "ENABLED" }) : undefined} addLabel="新增模板" />
      )}
      {tab === "dict" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索分组 / 编码 / 标签"
          onExport={() => exportCsv<DictEntry>("参数字典", [
            { header: "字典号", value: (d) => d.dictNo },
            { header: "分组", value: (d) => d.group },
            { header: "编码", value: (d) => d.code },
            { header: "标签", value: (d) => d.label },
            { header: "排序", value: (d) => d.sort },
            { header: "状态", value: (d) => (d.enabled ? "启用" : "停用") },
          ], (q.data?.list ?? []) as DictEntry[])}
          onAdd={canDict ? () => setDictForm({ sort: 0, enabled: true }) : undefined} addLabel="新增字典项" />
      )}
      {tab === "region" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索区域 ID / 名称"
          onExport={() => exportCsv<Region>("地区库", [
            { header: "区域 ID", value: (r) => r.regionId },
            { header: "名称", value: (r) => r.name },
            { header: "上级", value: (r) => r.parent || "-" },
            { header: "层级", value: (r) => r.level },
            { header: "城市数", value: (r) => r.cityCount },
          ], (q.data?.list ?? []) as Region[])}
          onAdd={canRegion ? () => setRegionForm({ level: 1, cityCount: 0, parent: "" }) : undefined} addLabel="新增地区" />
      )}
      {tab === "params" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索参数键 / 说明"
          onExport={() => exportCsv<SysParam>("系统参数", [
            { header: "参数键", value: (p) => p.paramKey },
            { header: "说明", value: (p) => p.label },
            { header: "取值", value: (p) => p.value },
            { header: "分组", value: (p) => p.groupName },
            { header: "更新时间", value: (p) => fmtTime(p.updatedAt) },
          ], (q.data?.list ?? []) as SysParam[])}
          onAdd={canParam ? () => setParamForm({ value: "", groupName: "" }) : undefined} addLabel="新增参数" />
      )}
      {tab === "openapi" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索应用号 / 名称 / AppKey"
          onExport={() => exportCsv<OpenApiApp>("OpenAPI 应用", [
            { header: "应用号", value: (a) => a.appNo },
            { header: "名称", value: (a) => a.name },
            { header: "AppKey", value: (a) => a.appKey },
            { header: "限流（次/秒）", value: (a) => a.rateLimit },
            { header: "状态", value: (a) => (a.status === "ACTIVE" ? "启用" : "停用") },
            { header: "创建时间", value: (a) => fmtTime(a.createdAt) },
          ], (q.data?.list ?? []) as OpenApiApp[])}
          onAdd={canOpenapi ? () => setOpenapiForm({ rateLimit: 10, status: "ACTIVE" }) : undefined} addLabel="新增应用" />
      )}
      {tab === "markets" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索国家 / 币种"
          onExport={() => exportCsv<MarketCountry>("多国家市场", [
            { header: "国家", value: (m) => `${m.name}（${m.countryCode}）` },
            { header: "币种", value: (m) => m.currency },
            { header: "时区", value: (m) => m.timezone },
            { header: "合规主体", value: (m) => m.compliance },
            { header: "开城数", value: (m) => m.cityCount },
            { header: "状态", value: (m) => MARKET_STATUS[m.status].label },
          ], (q.data?.list ?? []) as MarketCountry[])}
          onAdd={canMarket ? () => setMarketForm({ countryCode: "", name: "", currency: "AED", timezone: "Asia/Dubai", compliance: "规划", cityCount: 0, status: "PLANNED" }) : undefined} addLabel="新增国家市场" />
      )}

      {tab === "notify-log" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索流水号 / 模板号 / 目标 / 场景 / 失败原因"
          // 流水类页面必须可导出：对账短信账单、复盘失败批次都靠它（决策 §八-2，CSV 带 BOM）
          onExport={() => exportCsv<NotifyLog>("发送记录", [
            { header: "流水号", value: (l) => l.logNo },
            { header: "渠道", value: (l) => LOG_CHANNEL[l.channel] },
            { header: "模板号", value: (l) => l.templateNo },
            { header: "目标（脱敏）", value: (l) => l.target },
            { header: "场景", value: (l) => l.scene },
            { header: "发送时间", value: (l) => l.sentAt },
            { header: "状态", value: (l) => (l.status === "SENT" ? "已发送" : "发送失败") },
            { header: "失败原因", value: (l) => l.failReason },
            { header: "成本", value: (l) => l.cost },
            { header: "币种", value: (l) => l.currency },
          ], (q.data?.list ?? []) as NotifyLog[])}
        >
          <FilterSelect value={logChannel} onChange={(v) => { setLogChannel(v); setPage(1); }} allLabel="全部渠道" options={LOG_CHANNEL_OPTIONS} />
          <FilterSelect value={logStatus} onChange={(v) => { setLogStatus(v); setPage(1); }} allLabel="全部状态" options={LOG_STATUS_OPTIONS} />
        </Toolbar>
      )}
      {tab === "notify-blacklist" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索拉黑号 / 目标 / 操作人"
          onExport={() => exportCsv<NotifyBlacklist>("触达拉黑", [
            { header: "拉黑号", value: (b) => b.blockNo },
            { header: "目标", value: (b) => b.target },
            { header: "渠道", value: (b) => BL_CHANNEL[b.channel] },
            { header: "原因", value: (b) => BL_REASON[b.reason].label },
            { header: "拉黑时间", value: (b) => fmtTime(b.blockedAt) },
            { header: "操作人", value: (b) => b.blockedBy },
            { header: "到期时间", value: (b) => (b.expireAt ? fmtTime(b.expireAt) : "永久") },
            { header: "状态", value: (b) => (isReleased(b) ? "已解除" : "拉黑中") },
          ], (q.data?.list ?? []) as NotifyBlacklist[])}
          onAdd={canBlacklist ? () => setBlacklistForm({ channel: "SMS", reason: "MANUAL", blockedBy: "", target: "", expireAt: "" }) : undefined}
          addLabel="手动拉黑"
        >
          <FilterSelect value={blChannel} onChange={(v) => { setBlChannel(v); setPage(1); }} allLabel="全部渠道" options={BL_CHANNEL_OPTIONS} />
          <FilterSelect value={blReason} onChange={(v) => { setBlReason(v); setPage(1); }} allLabel="全部原因" options={BL_REASON} />
        </Toolbar>
      )}
      {tab === "login" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索国家码 / 国家名称"
          onExport={() => exportCsv<LoginSetting>("登录设置", [
            { header: "国家", value: (s) => (s.country === "*" ? "默认档（*）" : `${s.countryName}（${s.country}）`) },
            { header: "验证码登录", value: (s) => (s.otpEnabled ? "开" : "关") },
            { header: "密码登录", value: (s) => (s.passwordEnabled ? "开" : "关") },
            { header: "Apple 登录", value: (s) => (s.appleEnabled ? "开" : "关") },
            { header: "Google 登录", value: (s) => (s.googleEnabled ? "开" : "关") },
            { header: "OTP 有效期（秒）", value: (s) => (s.otpEnabled ? s.otpExpireSec : "-") },
            { header: "日发送上限（条/人）", value: (s) => (s.otpEnabled ? s.otpDailyLimit : "-") },
            { header: "强制实名", value: (s) => (s.forceRealName ? "强制" : "不强制") },
          ], (q.data?.list ?? []) as LoginSetting[])}
          onAdd={canLogin ? () => setLoginForm({ country: "", countryName: "", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false }) : undefined}
          addLabel="新增国家档"
        />
      )}
      {tab === "app-version" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索版本号 / 平台 / 更新说明"
          onExport={() => exportCsv<AppVersion>("应用版本", [
            { header: "平台", value: (v) => PLATFORM_LABEL[v.platform] },
            { header: "版本号", value: (v) => v.versionNo },
            { header: "构建号", value: (v) => v.buildNo },
            { header: "更新说明（中）", value: (v) => v.releaseNote },
            { header: "强更", value: (v) => (v.forceUpdate ? "强制更新" : "-") },
            { header: "最低支持", value: (v) => v.minSupported || "-" },
            { header: "灰度（%）", value: (v) => v.rolloutPercent },
            { header: "状态", value: (v) => VERSION_STATUS[v.status].label },
            { header: "发布时间", value: (v) => fmtTime(v.releasedAt) },
          ], (q.data?.list ?? []) as AppVersion[])}
          onAdd={canAppVersion ? () => setVersionForm({ platform: "IOS", versionNo: "", buildNo: 1, status: "DRAFT", releaseNote: "", releaseNoteEn: "", releaseNoteAr: "", forceUpdate: false, minSupported: "", rolloutPercent: 0, downloadUrl: "" }) : undefined}
          addLabel="新增版本"
        >
          <FilterSelect value={verPlatform} onChange={(v) => { setVerPlatform(v); setPage(1); }} allLabel="全部平台" options={PLATFORM_OPTIONS} />
        </Toolbar>
      )}
      {tab === "banks" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索银行代码 / 名称（中/英）/ SWIFT"
          onExport={() => exportCsv<BankEntry>("银行管理", [
            { header: "银行代码", value: (b) => b.bankCode },
            { header: "银行名称", value: (b) => `${b.bankName} · ${b.bankNameEn}` },
            { header: "国家", value: (b) => b.country },
            { header: "币种", value: (b) => b.currency },
            { header: "SWIFT 前缀", value: (b) => b.swiftPrefix },
            { header: "IBAN 长度", value: (b) => b.ibanLength },
            { header: "状态", value: (b) => (b.status === "ENABLED" ? "启用" : "停用") },
            ...(showArchived ? [{ header: "归档时间", value: (b: BankEntry) => (b.archivedAt ? fmtTime(b.archivedAt) : "-") }] : []),
          ], (q.data?.list ?? []) as BankEntry[])}
          onAdd={canBank ? () => setBankForm({ bankCode: "", bankName: "", bankNameEn: "", country: "AE", currency: "AED", swiftPrefix: "", ibanLength: 23, status: "ENABLED" }) : undefined}
          addLabel="新增银行"
        >
          <FilterSelect value={bankCountry} onChange={(v) => { setBankCountry(v); setPage(1); }} allLabel="全部国家" options={BANK_COUNTRY_OPTIONS} />
          <FilterSelect value={bankCurrency} onChange={(v) => { setBankCurrency(v); setPage(1); }} allLabel="全部币种" options={BANK_CURRENCY_OPTIONS} />
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "problems" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索问题号 / 标题（中/英/阿）/ 答复"
          onExport={() => exportCsv<ProblemEntry>("问题管理", [
            { header: "问题号", value: (p) => p.problemNo },
            { header: "分类", value: (p) => PROBLEM_CATEGORY[p.category] },
            { header: "标题（中）", value: (p) => p.title },
            { header: "建议处置", value: (p) => PROBLEM_ACTION[p.suggestedAction].label },
            { header: "排序", value: (p) => p.sortNo },
            { header: "状态", value: (p) => (p.status === "ENABLED" ? "启用" : "停用") },
            ...(showArchived ? [{ header: "归档时间", value: (p: ProblemEntry) => (p.archivedAt ? fmtTime(p.archivedAt) : "-") }] : []),
          ], (q.data?.list ?? []) as ProblemEntry[])}
          onAdd={canProblem ? () => setProblemForm({ category: "RENT", sortNo: 1, status: "ENABLED", title: "", titleEn: "", titleAr: "", answer: "", answerEn: "", answerAr: "", suggestedAction: "SELF_SERVICE" }) : undefined}
          addLabel="新增问题"
        >
          <FilterSelect value={probCategory} onChange={(v) => { setProbCategory(v); setPage(1); }} allLabel="全部分类" options={PROBLEM_CATEGORY_OPTIONS} />
          <FilterSelect value={probStatus} onChange={(v) => { setProbStatus(v); setPage(1); }} allLabel="全部状态" options={ENABLED_OPTIONS} />
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "tax" && (
        <Toolbar
          search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索国家 / 税种 / 开票抬头"
          onExport={() => exportCsv<TaxSetting>("税率与发票", [
            { header: "国家", value: (x) => `${x.countryName}（${x.country}）` },
            { header: "税种", value: (x) => x.taxName },
            { header: "税率（%）", value: (x) => x.ratePercent },
            { header: "计税方式", value: (x) => (x.includedInPrice ? "价内税" : "价外税") },
            { header: "税号（TRN）", value: (x) => x.trn },
            { header: "开票抬头", value: (x) => x.invoiceTitle },
            { header: "生效日期", value: (x) => x.effectiveFrom },
          ], (q.data?.list ?? []) as TaxSetting[])}
          onAdd={canTax ? () => setTaxForm({ country: "", countryName: "", taxName: "VAT", ratePercent: 5, includedInPrice: true, trn: "", invoiceTitle: "", effectiveFrom: "" }) : undefined}
          addLabel="新增国家税率"
        />
      )}

      {/* 权限降级：显式说明"仅可查看 + 缺哪个权限码"，不静默隐藏操作列 */}
      {tab === "notify-blacklist" && !canBlacklist && <ReadOnlyNotice what="触达拉黑维护" perm="system:notify_blacklist:update" />}
      {tab === "rules" && !canBizRule && <ReadOnlyNotice what="业务规则修改" perm="system:biz_rule:update" />}
      {tab === "login" && !canLogin && <ReadOnlyNotice what="登录设置修改" perm="system:login_setting:update" />}
      {tab === "app-version" && !canAppVersion && <ReadOnlyNotice what="应用版本发布" perm="system:app_version:release" />}
      {tab === "banks" && !canBank && <ReadOnlyNotice what="银行字典维护" perm="system:bank:update" />}
      {tab === "problems" && !canProblem && <ReadOnlyNotice what="问题字典维护" perm="system:problem:update" />}
      {tab === "tax" && !canTax && <ReadOnlyNotice what="税率与发票修改" perm="system:tax:update" />}

      {/* §11 业务规则：分区表单，不走列表/分页 */}
      {tab === "rules" && <BizRulesPanel canEdit={canBizRule} />}

      {tab === "notify-log" && (
        <DataTable
          rowKey={(l: NotifyLog) => l.logNo}
          columns={logCols}
          rows={q.data?.list as NotifyLog[]}
          loading={q.isLoading}
          sortKey={logSort.key}
          sortDir={logSort.dir}
          onSortChange={(key, dir) => { setLogSort({ key, dir }); setPage(1); }}
          empty="暂无发送记录——所选渠道/状态下今日尚无触达，或通知模板尚未启用"
        />
      )}
      {tab === "notify-blacklist" && <DataTable rowKey={(b: NotifyBlacklist) => b.blockNo} columns={blacklistCols} rows={q.data?.list as NotifyBlacklist[]} loading={q.isLoading} empty="暂无拉黑记录——没有用户退订或硬退信，也可手动拉黑投诉来源" />}
      {tab === "login" && <DataTable rowKey={(s: LoginSetting) => s.country} columns={loginCols} rows={q.data?.list as LoginSetting[]} loading={q.isLoading} empty="暂无登录设置——至少应保留一条 * 默认档，否则各国登录方式无处可依" />}
      {tab === "app-version" && <DataTable rowKey={(v: AppVersion) => v.versionId} columns={versionCols} rows={q.data?.list as AppVersion[]} loading={q.isLoading} empty="暂无应用版本——所选平台还没发过版，先新增一条草稿再灰度" />}
      {tab === "banks" && <DataTable rowKey={(b: BankEntry) => b.bankCode} columns={bankCols} rows={q.data?.list as BankEntry[]} loading={q.isLoading} rowClassName={archivedRowClass} empty="暂无银行——所选国家/币种下没有可用银行（已归档的默认不显示，可打开「显示已归档」查看），提现收款账户将无法选择，请先新增银行" />}
      {tab === "problems" && <DataTable rowKey={(p: ProblemEntry) => p.problemNo} columns={problemCols} rows={q.data?.list as ProblemEntry[]} loading={q.isLoading} rowClassName={archivedRowClass} empty="暂无问题条目——C 端报障下拉与客服快捷答复都取自这里（已归档的默认不显示，可打开「显示已归档」查看），建议先补常见问题" />}
      {tab === "tax" && <DataTable rowKey={(x: TaxSetting) => x.country} columns={taxCols} rows={q.data?.list as TaxSetting[]} loading={q.isLoading} empty="暂无税率配置——未配置的国家按不含税出账，开票会缺税号" />}

      {tab === "vendors" && <DataTable rowKey={(v: Vendor) => v.vendorCode} columns={vendorCols} rows={vendorsQ.data} loading={vendorsQ.isLoading} empty="暂无供应商——还没有硬件厂商注册 driver，设备台账将无法接入，请先由平台管理员登记供应商" />}
      {tab === "payment" && <DataTable rowKey={(c: PaymentChannel) => c.channelCode} columns={paymentCols} rows={q.data?.list as PaymentChannel[]} loading={q.isLoading} empty="暂无支付渠道——未配置渠道时 C 端无法下单支付，请先新增并启用至少一个渠道" />}
      {tab === "notify" && <DataTable rowKey={(t: NotifyTemplate) => t.templateNo} columns={notifyCols} rows={q.data?.list as NotifyTemplate[]} loading={q.isLoading} empty="暂无通知模板——OTP/借还/扣费短信都取自这里，请先新增模板再启用" />}
      {tab === "dict" && <DataTable rowKey={(d: DictEntry) => d.dictNo} columns={dictCols} rows={q.data?.list as DictEntry[]} loading={q.isLoading} empty="暂无字典项——所选分组下还没有枚举值，请新增字典项供下拉与状态展示使用" />}
      {tab === "region" && <DataTable rowKey={(r: Region) => r.regionId} columns={regionCols} rows={q.data?.list as Region[]} loading={q.isLoading} empty="暂无地区——站点与点位的归属地取自这里，请先建国家/城市层级" />}
      {tab === "params" && <DataTable rowKey={(p: SysParam) => p.paramKey} columns={paramCols} rows={q.data?.list as SysParam[]} loading={q.isLoading} empty="暂无系统参数——超时/重试等全局开关都在这里，未配置时按代码默认值运行" />}
      {tab === "openapi" && <DataTable rowKey={(a: OpenApiApp) => a.appNo} columns={openapiCols} rows={q.data?.list as OpenApiApp[]} loading={q.isLoading} empty="暂无 OpenAPI 应用——还没有合作方接入，需要对外开放接口时在此新增应用并分配 AppKey" />}
      {tab === "markets" && <DataTable rowKey={(m: MarketCountry) => m.countryCode} columns={marketCols} rows={q.data?.list as MarketCountry[]} loading={q.isLoading} empty="暂无国家市场——开城前需先登记国家、币种与合规主体，否则该国无法上线" />}
      {tab !== "vendors" && tab !== "rules" && q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      {/* 供应商 配置抽屉（保留）*/}
      <Drawer
        open={!!edit}
        onOpenChange={(o) => !o && setEdit(null)}
        title={`配置供应商 ${edit?.vendorCode ?? ""}`}
        desc="driver 接入参数（mock 保存到内存；接后端写 gw_vendor_config）"
        footer={
          <>
            <Button variant="outline" onClick={() => setEdit(null)}>取消</Button>
            <Button onClick={() => form.vendorCode && save.mutate(form as Vendor)} disabled={save.isPending}>保存</Button>
          </>
        }
      >
        <Field label="名称"><Input value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="接入方式">
          <Select className="w-full" value={form.accessMode ?? "HTTP_API"} onChange={(e) => setForm({ ...form, accessMode: e.target.value as AccessMode })}>
            <option value="TCP">TCP 私有协议</option>
            <option value="MQTT">MQTT 直连</option>
            <option value="HTTP_API">HTTP 云对接</option>
          </Select>
        </Field>
        <Field label="API 基址（云对接型）"><Input value={form.apiBase ?? ""} onChange={(e) => setForm({ ...form, apiBase: e.target.value })} placeholder="https://api.vendor.example" /></Field>
        <Field label="状态">
          <Select className="w-full" value={form.status ?? "ENABLED"} onChange={(e) => setForm({ ...form, status: e.target.value as Vendor["status"] })}>
            <option value="ENABLED">启用</option>
            <option value="DISABLED">停用</option>
          </Select>
        </Field>
      </Drawer>

      {/* 支付渠道 配置抽屉（密钥仅掩码，真实值由后端保管）*/}
      <FormDrawer open={!!paymentForm} onOpenChange={(o) => !o && setPaymentForm(null)}
        titleNew="新增支付渠道" titleEdit={`配置支付渠道 ${paymentForm?.channelCode ?? ""}`} isEdit={!!paymentForm?.channelCode}
        fields={PAYMENT_FIELDS} value={(paymentForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPaymentForm(v as Partial<PaymentChannel>)}
        onSubmit={() => paymentForm && savePayment.mutate(paymentForm)} submitting={savePayment.isPending} />

      {/* 通知模板 编辑抽屉 */}
      <FormDrawer open={!!notifyForm} onOpenChange={(o) => !o && setNotifyForm(null)}
        titleNew="新增通知模板" titleEdit={`编辑通知模板 ${notifyForm?.templateNo ?? ""}`} isEdit={!!notifyForm?.templateNo}
        fields={NOTIFY_FIELDS} value={(notifyForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setNotifyForm(v as Partial<NotifyTemplate>)}
        onSubmit={() => notifyForm && saveNotify.mutate(notifyForm)} submitting={saveNotify.isPending} />

      {/* 参数字典 编辑抽屉 */}
      <FormDrawer open={!!dictForm} onOpenChange={(o) => !o && setDictForm(null)}
        titleNew="新增字典项" titleEdit={`编辑字典项 ${dictForm?.dictNo ?? ""}`} isEdit={!!dictForm?.dictNo}
        fields={DICT_FIELDS} value={(dictForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setDictForm(v as Partial<DictEntry>)}
        onSubmit={() => dictForm && saveDict.mutate(dictForm)} submitting={saveDict.isPending} />

      {/* 地区库 编辑抽屉 */}
      <FormDrawer open={!!regionForm} onOpenChange={(o) => !o && setRegionForm(null)}
        titleNew="新增地区" titleEdit={`编辑地区 ${regionForm?.regionId ?? ""}`} isEdit={!!regionForm?.regionId}
        fields={REGION_FIELDS} value={(regionForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRegionForm(v as Partial<Region>)}
        onSubmit={() => regionForm && saveRegion.mutate(regionForm)} submitting={saveRegion.isPending} />

      {/* 系统参数 编辑抽屉 */}
      <FormDrawer open={!!paramForm} onOpenChange={(o) => !o && setParamForm(null)}
        titleNew="新增系统参数" titleEdit={`编辑系统参数 ${paramForm?.paramKey ?? ""}`} isEdit={!!paramForm?.paramKey}
        fields={PARAM_FIELDS} value={(paramForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setParamForm(v as Partial<SysParam>)}
        onSubmit={() => paramForm && saveParam.mutate(paramForm)} submitting={saveParam.isPending} />

      {/* OpenAPI 应用 编辑抽屉 */}
      <FormDrawer open={!!openapiForm} onOpenChange={(o) => !o && setOpenapiForm(null)}
        titleNew="新增 OpenAPI 应用" titleEdit={`编辑 OpenAPI 应用 ${openapiForm?.appNo ?? ""}`} isEdit={!!openapiForm?.appNo}
        fields={OPENAPI_FIELDS} value={(openapiForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setOpenapiForm(v as Partial<OpenApiApp>)}
        onSubmit={() => openapiForm && saveOpenapi.mutate(openapiForm)} submitting={saveOpenapi.isPending} />

      {/* 多国家市场：国家码即主键，新增时必填才能提交 */}
      <FormDrawer open={!!marketForm} onOpenChange={(o) => !o && setMarketForm(null)}
        titleNew="新增国家市场" titleEdit={`编辑国家市场 ${marketForm?.name ?? ""}`} isEdit={!!marketForm?.countryCode}
        fields={MARKET_FIELDS} value={(marketForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setMarketForm(v as Partial<MarketCountry>)}
        onSubmit={() => {
          if (!marketForm) return;
          if (!marketForm.countryCode?.trim()) { notify.error("国家码必填（ISO alpha-2，如 AE）"); return; }
          saveMarket.mutate({ ...marketForm, countryCode: marketForm.countryCode.trim().toUpperCase() });
        }} submitting={saveMarket.isPending} />

      {/* §10 手动拉黑：新增时补拉黑时间；expireAt 留空 = 永久 */}
      <FormDrawer open={!!blacklistForm} onOpenChange={(o) => !o && setBlacklistForm(null)}
        titleNew="手动拉黑" titleEdit={`编辑拉黑 ${blacklistForm?.blockNo ?? ""}`} isEdit={!!blacklistForm?.blockNo}
        fields={BLACKLIST_FIELDS} value={(blacklistForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setBlacklistForm(v as Partial<NotifyBlacklist>)}
        onSubmit={() => {
          if (!blacklistForm) return;
          saveBlacklist.mutate({
            ...blacklistForm,
            expireAt: blacklistForm.expireAt || null,
            blockedAt: blacklistForm.blockedAt || new Date().toISOString(),
          });
        }} submitting={saveBlacklist.isPending} />

      {/* §12 登录设置：国家码是主键，新增必填 + 自动大写（`*` 默认档除外），编辑只读（规格 §17.1-10）*/}
      <FormDrawer open={!!loginForm} onOpenChange={(o) => !o && setLoginForm(null)}
        titleNew="新增国家登录档" titleEdit={`编辑登录设置 ${loginForm?.country ?? ""}`} isEdit={!!loginForm?.country}
        fields={LOGIN_FIELDS} value={(loginForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setLoginForm(v as Partial<LoginSetting>)}
        onSubmit={() => {
          if (!loginForm) return;
          const code = (loginForm.country ?? "").trim();
          if (!loginForm.otpEnabled && !loginForm.passwordEnabled && !loginForm.appleEnabled && !loginForm.googleEnabled) {
            notify.error("至少要保留一种登录方式，否则该国用户无法登录");
            return;
          }
          saveLogin.mutate({ ...loginForm, country: code === "*" ? "*" : code.toUpperCase() });
        }} submitting={saveLogin.isPending} />

      {/* §13 应用版本：强更必须有最低支持版本——这条用 required/disabledWhen 表达不了（依赖另一字段的真值），故在提交时拦截 */}
      <FormDrawer open={!!versionForm} onOpenChange={(o) => !o && setVersionForm(null)}
        titleNew="新增应用版本" titleEdit={`编辑版本 ${versionForm?.platform ? PLATFORM_LABEL[versionForm.platform] : ""} ${versionForm?.versionNo ?? ""}`} isEdit={!!versionForm?.versionId}
        fields={VERSION_FIELDS} value={(versionForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setVersionForm(v as Partial<AppVersion>)}
        onSubmit={() => {
          if (!versionForm) return;
          if (versionForm.forceUpdate && !versionForm.minSupported?.trim()) {
            notify.error("强制更新时必须填写「最低支持版本」，否则无法判断该拦谁");
            return;
          }
          const pct = Number(versionForm.rolloutPercent ?? 0);
          if (!Number.isFinite(pct) || pct < 0 || pct > 100) { notify.error("灰度比例需在 0~100 之间"); return; }
          if (versionForm.forceUpdate && pct < 100) { notify.error("强制更新必须 100% 下发，否则部分用户被拦在旧版本无法升级"); return; }
          saveVersion.mutate({ ...versionForm, rolloutPercent: pct, releasedAt: versionForm.status === "RELEASED" ? (versionForm.releasedAt ?? new Date().toISOString()) : versionForm.releasedAt ?? null });
        }} submitting={saveVersion.isPending} />

      {/* §14 银行管理：银行代码/国家码新增必填 + 自动大写，编辑只读 */}
      <FormDrawer open={!!bankForm} onOpenChange={(o) => !o && setBankForm(null)}
        titleNew="新增银行" titleEdit={`编辑银行 ${bankForm?.bankName ?? ""}`} isEdit={!!bankForm?.bankCode}
        fields={BANK_FIELDS} value={(bankForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setBankForm(v as Partial<BankEntry>)}
        onSubmit={() => {
          if (!bankForm) return;
          saveBank.mutate({
            ...bankForm,
            bankCode: (bankForm.bankCode ?? "").trim().toUpperCase(),
            country: (bankForm.country ?? "").trim().toUpperCase(),
            currency: (bankForm.currency ?? "").trim().toUpperCase(),
            swiftPrefix: (bankForm.swiftPrefix ?? "").trim().toUpperCase(),
          });
        }} submitting={saveBank.isPending} />

      {/* §15 问题管理：三语标题 + 三语答复共 6 个输入，分「基本信息 / 三语内容 / 处置」三区 */}
      <FormDrawer open={!!problemForm} onOpenChange={(o) => !o && setProblemForm(null)}
        titleNew="新增问题" titleEdit={`编辑问题 ${problemForm?.problemNo ?? ""}`} isEdit={!!problemForm?.problemNo}
        fields={PROBLEM_FIELDS} value={(problemForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setProblemForm(v as Partial<ProblemEntry>)}
        onSubmit={() => problemForm && saveProblem.mutate(problemForm)}
        submitting={saveProblem.isPending} />

      {/* §16 税率与发票：国家码是主键，新增必填 + 自动大写，编辑只读 */}
      <FormDrawer open={!!taxForm} onOpenChange={(o) => !o && setTaxForm(null)}
        titleNew="新增国家税率" titleEdit={`编辑税率 ${taxForm?.countryName ?? ""}`} isEdit={!!taxForm?.country}
        fields={TAX_FIELDS} value={(taxForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setTaxForm(v as Partial<TaxSetting>)}
        onSubmit={() => {
          if (!taxForm) return;
          saveTax.mutate({ ...taxForm, country: (taxForm.country ?? "").trim().toUpperCase() });
        }} submitting={saveTax.isPending} />

      {/* §9 失败详情：只读抽屉（长文本报错列表里放不下）*/}
      <Drawer
        open={!!logDetail}
        onOpenChange={(o) => !o && setLogDetail(null)}
        title={`发送失败详情 ${logDetail?.logNo ?? ""}`}
        desc="原始报错来自上游通道回执，排障与找运营商对质都靠它"
        footer={<Button variant="outline" onClick={() => setLogDetail(null)}>{t("common.cancel")}</Button>}
      >
        <Field label="渠道 / 模板"><span className="tabular-nums">{logDetail ? `${LOG_CHANNEL[logDetail.channel]} · ${logDetail.templateNo}` : "-"}</span></Field>
        <Field label="目标（脱敏）"><span className="tabular-nums">{logDetail?.target ?? "-"}</span></Field>
        <Field label="场景">{logDetail?.scene ?? "-"}</Field>
        <Field label="发送时间">{fmtTime(logDetail?.sentAt)}</Field>
        <Field label="计费">{logDetail ? money(logDetail.cost, logDetail.currency) : "-"}</Field>
        <Field label="失败原因">
          <div className="rounded-lg bg-muted px-3.5 py-2 text-sm">{logDetail?.failReason ?? "-"}</div>
        </Field>
        <Field label="下一步">
          <span className="text-muted-foreground">
            号码/邮箱无效或用户退订的，请到「触达拉黑」登记，避免持续扣费重发。
          </span>
        </Field>
      </Drawer>

      {/* 统一二次确认弹窗（解除拉黑 / 版本回滚）*/}
      {dialog}
    </div>
  );
}

export default function SystemPage() {
  return <Suspense fallback={null}><SystemInner /></Suspense>;
}
