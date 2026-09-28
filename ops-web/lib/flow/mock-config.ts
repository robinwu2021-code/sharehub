// 配置管理的 mock：审批模板 · SLA 规则 · 工作日历 · 队列 · 接入应用 · webhook 投递 · 定义版本。
//
// 与服务端同构的几点：
//  - 审批模板**版本 = 内容哈希，只增不改**；内容没变再发布是幂等的（created=false），
//    在途审批按提交时版本走完（runningOnOlderVersions）；
//  - 校验失败 422，**一次返回全部错误**，每条带字段路径（页面挂到对应字段）；
//  - 凭证只在创建 / 轮换时返回一次，之后读不到；
//  - 改 SLA / 日历 / 队列只影响新单（运行中的按开单时的规则走完）—— mock 不回溯改旧单。
import { fail, notFound } from "@/lib/biz-error";
import { ME } from "./mock-db";
import { pendingCountOf } from "./mock-approvals";
import { FlowValidationError } from "./errors";
import { requirePerm } from "./mock-auth";
import { ALL_MODE_KINDS } from "./types";
import type {
  AppRow, ApprovalTemplate, ApproverRef, DefinitionVersionRow, DeliveryRow, FieldError, I18nText, TemplateStep, TemplateSummary,
  TemplateVersion, PublishResult, QueueGroup, SlaPolicy, WorkCalendar,
} from "./types";

const MIN = 60_000;
const HOUR = 60 * MIN;

/** 内容哈希（8 位十六进制）：同内容同版本号 */
export function contentHash(def: ApprovalTemplate): string {
  const text = JSON.stringify({ n: def.name, s: def.steps, r: def.rules });
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
  return h.toString(16).padStart(8, "0");
}

interface ProcessRec {
  def: ApprovalTemplate;
  enabled: boolean;
  versions: (TemplateVersion & { def: ApprovalTemplate })[];
}

/** 服务按请求语言 → en → 任意一种回退；mock 的请求语言固定中文 */
const pick = (t: I18nText) => t.zh || t.en || t.ar || "";
const zh = (text: string): I18nText => ({ zh: text });

let processes: ProcessRec[] = [];
let slaPolicies: SlaPolicy[] = [];
let calendars: WorkCalendar[] = [];
let queues: QueueGroup[] = [];
let apps: AppRow[] = [];
let deliveries: DeliveryRow[] = [];
let otherDefs: DefinitionVersionRow[] = [];
let secretSeq = 0;

const step = (code: string, name: string, kind: "GROUP" | "ROLE" | "PERM" | "FIELD", value: string, hours: number,
  when?: TemplateStep["when"]): TemplateStep => ({ code, name: zh(name), approver: { kind, value }, mode: "ANY", dueHours: hours, ...(when ? { when } : {}) });
const rules = (crossStepExclusive: boolean) => ({ selfApproval: false, crossStepExclusive });

export function resetConfigDb(now: number = Date.now()) {
  secretSeq = 0;
  const loc: ApprovalTemplate = { code: "LOC_CONTRACT", name: { zh: "进场合同", en: "Venue contract" }, rules: rules(true), steps: [
    step("ops", "运营审批", "PERM", "location:contract:audit", 8),
    step("fin", "财务会签", "PERM", "location:contract:cosign", 24, { field: "needsCosign", op: "TRUE" }),
  ] };
  const locV1: ApprovalTemplate = { ...loc, steps: loc.steps.map((s) => (s.code === "fin" ? { ...s, dueHours: 48 } : s)) };
  const mk = (def: ApprovalTemplate, version: string, atMs: number, channel: "API" | "CONSOLE", by: string, note?: string) =>
    ({ def, version, atMs, channel, by, note, current: false });
  processes = [
    { def: loc, enabled: true, versions: [
      mk(locV1, "88d3c0e5", now - 2.5 * 24 * HOUR, "API", "SHAREHUB", "上线初版"),
      mk(loc, "a3f8c2d1", now - 12 * HOUR, "CONSOLE", "Omar", "会签时限 48h → 24h"),
    ] },
    { def: { code: "FIN_WITHDRAWAL", name: zh("提现审批"), rules: rules(false), steps: [step("fin", "财务审批", "PERM", "finance:withdrawal:audit", 24)] },
      enabled: true, versions: [] },
    { def: { code: "AGT_APPLY", name: zh("代理入驻"), rules: rules(false), steps: [step("ops", "运营审核", "PERM", "agent:apply:audit", 48)] },
      enabled: true, versions: [] },
    { def: { code: "CS_REFUND", name: zh("退款审批"), rules: rules(false), steps: [step("cs", "客服主管审批", "ROLE", "cs-lead", 4)] },
      enabled: true, versions: [] },
  ];
  for (const p of processes) {
    if (!p.versions.length) p.versions.push(mk(p.def, contentHash(p.def), now - 3 * 24 * HOUR, "API", "SHAREHUB", "上线初版"));
  }
  // SLA：通用策略形状（后端 O2-8）。扁平规则的换算按后端给的口径：一个工单类型一条策略（首次响应 + 解决两个指标），
  // 每个优先级一行 when {priority EQ …}，「其余」是最后一行兜底（不带 when），按营业时间计的填 calendar。
  const std = (): SlaPolicy["metrics"] => [
    { code: "FIRST_RESPONSE", name: { zh: "首次响应", en: "First response", ar: "الاستجابة الأولى" } },
    { code: "RESOLUTION", name: { zh: "解决", en: "Resolution", ar: "الحل" } },
  ];
  const pri = (value: string) => ({ field: "priority", op: "EQ" as const, value });
  slaPolicies = [
    { code: "ops", name: { zh: "运维工单", en: "Maintenance tickets" }, metrics: std(), warnAtPercent: 80, targets: [
      { when: pri("URGENT"), targetsMin: { FIRST_RESPONSE: 30, RESOLUTION: 240 } },
      { when: pri("HIGH"), targetsMin: { FIRST_RESPONSE: 120, RESOLUTION: 480 }, calendar: "dubai" },
      { targetsMin: { FIRST_RESPONSE: 240, RESOLUTION: 1440 }, calendar: "dubai" },
    ] },
    { code: "cs-report", name: { zh: "客服报障" }, metrics: std(), warnAtPercent: 80, targets: [
      { targetsMin: { FIRST_RESPONSE: 15, RESOLUTION: 240 } },
    ] },
    { code: "review_sla", name: { zh: "证据审核时限", en: "Evidence review" }, warnAtPercent: 80,
      metrics: [{ code: "RESOLUTION", name: { zh: "解决", en: "Resolution" } }], targets: [{ targetsMin: { RESOLUTION: 240 } }] },
  ].map((p) => ({ ...p, version: slaHash(p) }));
  calendars = [{ code: "dubai", name: "迪拜营业时间", timeZone: "Asia/Dubai", weekdays: [1, 2, 3, 4, 5, 6], open: "10:00", close: "22:00",
    holidays: [{ date: "2026-12-02", name: "国庆" }, { date: "2026-12-03", name: "国庆" }] }];
  queues = [
    { code: "merchant", name: "商户资质", kind: "QUEUE", orderBy: "PRIORITY", claimTimeoutSec: 30 * 60 },
    { code: "goods", name: "商品审核", kind: "QUEUE", orderBy: "CREATED", claimTimeoutSec: 30 * 60 },
    { code: "factory", name: "验厂", kind: "QUEUE", orderBy: "PRIORITY", claimTimeoutSec: 60 * 60 },
  ];
  apps = [
    { id: "app_sharehub", code: "SHAREHUB", name: "ShareHub 共享充电", callbackUrl: "https://powerbank.ichain.top/api/opskit/callback", timeZone: "Asia/Dubai", enabled: true },
    { id: "app_shop", code: "SHOP", name: "ai-shop 电商", callbackUrl: "https://shop.neargo.ai/api/opskit/callback", timeZone: "Asia/Shanghai", enabled: true },
  ];
  const d = (id: string, eventType: string, app: string, idemKey: string, status: DeliveryRow["status"], ago: number, extra: Partial<DeliveryRow>): DeliveryRow =>
    ({ id, eventType, app, idemKey, status, attempts: 1, maxAttempts: 8, atMs: now - ago, ...extra });
  deliveries = [
    d("dl_001", "ticket.sla.breached", "SHAREHUB", "CS26092712:9", "DELIVERED", 3 * MIN, { httpStatus: 200, durationMs: 86, attempts: 1 }),
    d("dl_002", "approval.completed", "SHAREHUB", "AP2609260031:1", "FAILED", 40 * MIN, { error: "连接超时", attempts: 3 }),
    d("dl_003", "ticket.transitioned", "SHOP", "AS26092240:4", "DELIVERED", 55 * MIN, { httpStatus: 200, durationMs: 120, attempts: 2 }),
    d("dl_004", "instance.awaiting_confirmation", "SHAREHUB", "IN2609270003:2", "RETRYING", 70 * MIN, { httpStatus: 503, error: "对端 503", attempts: 2 }),
    d("dl_005", "ticket.created", "SHAREHUB", "WO26092701:1", "DELIVERED", 3.4 * HOUR, { httpStatus: 200, durationMs: 64 }),
  ];
  otherDefs = [
    { kind: "TICKET_TYPE", code: "PB_MAINTENANCE", version: "7be04412", publishedAtMs: now - 12 * HOUR, publishedBy: "SHAREHUB", channel: "API" },
    { kind: "PROCESS", code: "SHOP_AFTER_SALE", version: "c91177aa", publishedAtMs: now - 29 * HOUR, publishedBy: "SHOP", channel: "API" },
    { kind: "OPTION_SET", code: "ticket-source", version: "9a0c7e12", publishedAtMs: now - 60 * HOUR, publishedBy: "SHAREHUB", channel: "API" },
    { kind: "SLA_POLICY", code: "ops-urgent", version: "5e21b0c4", publishedAtMs: now - 50 * HOUR, publishedBy: "Omar", channel: "CONSOLE" },
  ];
}
resetConfigDb();

// ── 审批模板 ────────────────────────────────────────────────────────────
function prec(code: string): ProcessRec {
  const p = processes.find((x) => x.def.code === code);
  if (!p) notFound("审批模板", "Approval template", code);
  return p;
}
const currentVersion = (p: ProcessRec) => p.versions[p.versions.length - 1];

export function listTemplates(): TemplateSummary[] {
  return processes.map((p) => ({
    code: p.def.code, name: pick(p.def.name),
    stepsSummary: p.def.steps.map((s) => `${pick(s.name)}${s.when ? "（条件）" : ""}`).join(" → "),
    version: currentVersion(p).version, running: pendingCountOf(p.def.code), enabled: p.enabled,
  }));
}

export function getTemplate(code: string): ApprovalTemplate {
  return structuredClone(prec(code).def);
}

export function templateVersions(code: string): TemplateVersion[] {
  const p = prec(code);
  const cur = currentVersion(p).version;
  return [...p.versions].reverse().map(({ def: _d, ...v }) => ({ ...v, current: v.version === cur }));
}

/** 校验：一次返回全部错误 */
function validate(def: ApprovalTemplate, isNew: boolean): FieldError[] {
  const e: FieldError[] = [];
  if (isNew && !/^[A-Z][A-Z0-9_]{2,31}$/.test(def.code)) e.push({ path: "code", error: "代号 3–32 位，大写字母开头，只含大写字母、数字、下划线" });
  if (isNew && processes.some((p) => p.def.code === def.code)) e.push({ path: "code", error: "代号已存在" });
  if (!pick(def.name)) e.push({ path: "name", error: "模板名称必填" });
  if (!def.steps.length) e.push({ path: "steps", error: "至少要有一个步骤" });
  if (def.steps.length && def.steps.every((s) => s.when)) e.push({ path: "steps", error: "至少要有一个必经步骤（不带进入条件）" });
  def.steps.forEach((s, i) => {
    const at = (k: string) => `steps[${i}].${k}`;
    if (!pick(s.name)) e.push({ path: at("name"), error: "步骤名称必填" });
    if (!s.approver.value.length) e.push({ path: at("approver.value"), error: s.approver.kind === "FIELD" ? "要填给出审批人的字段代号" : "审批人必填" });
    if (s.mode === "ALL" && !ALL_MODE_KINDS.includes(s.approver.kind)) e.push({ path: at("mode"), error: "会签只能用于成员 / 组 / 字段指定（角色、权限码的人选不可枚举）" });
    if (s.dueHours !== undefined && !(s.dueHours > 0 && s.dueHours <= 720)) e.push({ path: at("dueHours"), error: "步骤时限 1–720 小时" });
    if (s.when) {
      if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(s.when.field)) e.push({ path: at("when.field"), error: "属性名只能是字母、数字、下划线，字母开头" });
      if ((s.when.op === "EQ" || s.when.op === "IN") && !s.when.value?.trim()) e.push({ path: at("when.value"), error: "「等于 / 属于」要填比较值" });
    }
  });
  return e;
}

/** PUT /workspaces/{ws}/processes/{code}：幂等发布 */
/** 去空白；成员列表去掉空项与重复 */
function cleanApprover(a: ApproverRef): ApproverRef {
  return a.kind === "MEMBER"
    ? { kind: a.kind, value: [...new Set(a.value.map((v) => v.trim()).filter(Boolean))] }
    : { kind: a.kind, value: a.value.trim() };
}

export function publishTemplate(def: ApprovalTemplate, note: string, isNew: boolean, now: number = Date.now()): PublishResult {
  requirePerm("flow:process:manage");
  const trimAll = (t: I18nText): I18nText =>
    Object.fromEntries(Object.entries(t).map(([k, v]) => [k, v?.trim()]).filter(([, v]) => v)) as I18nText;
  const clean: ApprovalTemplate = {
    ...def, name: trimAll(def.name),
    steps: def.steps.map((s, i) => ({ ...s, code: s.code || `s${i + 1}`, name: trimAll(s.name), approver: cleanApprover(s.approver) })),
  };
  const errors = validate(clean, isNew);
  if (errors.length) throw new FlowValidationError(`保存被拒：有 ${errors.length} 处需要修改`, errors);
  const version = contentHash(clean);
  if (isNew) {
    processes.push({ def: clean, enabled: true, versions: [{ def: clean, version, atMs: now, channel: "CONSOLE", by: ME.displayName, note: note || "新建", current: true }] });
    return { version, created: true, runningOnOlderVersions: 0 };
  }
  const p = prec(def.code);
  if (JSON.stringify(currentVersion(p).def) === JSON.stringify(clean) || currentVersion(p).version === version) {
    return { version: currentVersion(p).version, created: false, runningOnOlderVersions: 0 };
  }
  p.def = clean;
  p.versions.push({ def: clean, version, atMs: now, channel: "CONSOLE", by: ME.displayName, note: note.trim() || undefined, current: true });
  return { version, created: true, runningOnOlderVersions: pendingCountOf(def.code) };
}

/** POST …/approval-templates/{code}/disable · /enable：停用 = 不再接受新提交，在途走完 */
export function setTemplateEnabled(code: string, enabled: boolean): void {
  requirePerm("flow:process:manage");
  prec(code).enabled = enabled;
}

// ── SLA 规则 · 日历 · 队列 ────────────────────────────────────────────────
export const listSlaPolicies = () => structuredClone(slaPolicies);
export const listCalendars = () => structuredClone(calendars);
export const listQueueGroups = () => structuredClone(queues);

/** 内容哈希（与审批模板同法）：name / metrics / targets / warnAtPercent 不变即同版本 */
function slaHash(p: Pick<SlaPolicy, "name" | "metrics" | "targets" | "warnAtPercent">): string {
  const text = JSON.stringify({ n: p.name, m: p.metrics, t: p.targets, w: p.warnAtPercent ?? null });
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
  return h.toString(16).padStart(8, "0");
}

/** PUT …/sla-policies/{code}：校验一次给全（路径按策略字段），内容不变 created=false（幂等） */
export function upsertSlaPolicy(p: SlaPolicy, isNew = false): { version: string; created: boolean } {
  requirePerm("flow:config:manage");
  const e: FieldError[] = [];
  if (isNew && !/^[a-z][a-z0-9_-]{1,31}$/.test(p.code)) e.push({ path: "code", error: "代号 2–32 位小写字母、数字、下划线、连字符" });
  if (isNew && slaPolicies.some((x) => x.code === p.code)) e.push({ path: "code", error: "代号已存在" });
  if (!Object.values(p.name).some((v) => v?.trim())) e.push({ path: "name", error: "名称必填（至少一种语言）" });
  if (!p.metrics.length) e.push({ path: "metrics", error: "至少要有一个指标" });
  const codes = new Set(p.metrics.map((m) => m.code));
  if (!p.targets.length) e.push({ path: "targets", error: "至少要有一行目标" });
  p.targets.forEach((t, i) => {
    // 与 OpsKit SlaPolicyValidator 同口径：错误挂在兜底行「后面那一行」（它才是永远匹配不到的）
    if (!t.when && i !== p.targets.length - 1) e.push({ path: `targets[${i + 1}]`, error: "兜底行（不带条件）后面的这一行永远匹配不到 —— 兜底行只能放最后" });
    for (const [k, v] of Object.entries(t.targetsMin)) {
      if (!codes.has(k)) e.push({ path: `targets[${i}].targetsMin.${k}`, error: `指标 ${k} 不在本策略的指标里` });
      else if (!(v > 0)) e.push({ path: `targets[${i}].targetsMin.${k}`, error: "时限必须大于 0 分钟" });
    }
    if (t.calendar && !calendars.some((c) => c.code === t.calendar)) e.push({ path: `targets[${i}].calendar`, error: "工作日历不存在" });
  });
  if (p.warnAtPercent !== undefined && !(p.warnAtPercent >= 1 && p.warnAtPercent <= 99)) e.push({ path: "warnAtPercent", error: "预警比例 1–99" });
  if (e.length) throw new FlowValidationError(`保存被拒：有 ${e.length} 处需要修改`, e);
  const { version: _v, inherited: _i, note: _n, ...content } = p;
  const version = slaHash(content);
  const i = slaPolicies.findIndex((x) => x.code === p.code);
  if (i >= 0 && slaPolicies[i].version === version) return { version, created: false };
  const saved = { ...structuredClone(content), version };
  if (i >= 0) slaPolicies[i] = saved; else slaPolicies.push(saved);
  return { version, created: true };
}

export function upsertCalendar(c: WorkCalendar): WorkCalendar {
  requirePerm("flow:config:manage");
  const e: FieldError[] = [];
  if (!c.name.trim()) e.push({ path: "name", error: "名称必填" });
  if (!c.weekdays.length) e.push({ path: "weekdays", error: "至少选一个工作日" });
  if (!(c.open < c.close)) e.push({ path: "close", error: "下班时间要晚于上班时间" });
  if (e.length) throw new FlowValidationError(`保存被拒：有 ${e.length} 处需要修改`, e);
  const i = calendars.findIndex((x) => x.code === c.code);
  if (i >= 0) calendars[i] = c; else calendars.push(c);
  return c;
}

export function upsertQueueGroup(q: QueueGroup, isNew: boolean): QueueGroup {
  requirePerm("flow:config:manage");
  const e: FieldError[] = [];
  if (isNew && !/^[a-z][a-z0-9-]{1,31}$/.test(q.code)) e.push({ path: "code", error: "代号 2–32 位小写字母、数字、连字符" });
  if (isNew && queues.some((x) => x.code === q.code)) e.push({ path: "code", error: "代号已存在" });
  if (!q.name.trim()) e.push({ path: "name", error: "名称必填" });
  if (!(q.claimTimeoutSec >= 300 && q.claimTimeoutSec <= 14_400)) e.push({ path: "claimTimeoutSec", error: "领取超时 5–240 分钟" });
  if (e.length) throw new FlowValidationError(`保存被拒：有 ${e.length} 处需要修改`, e);
  const i = queues.findIndex((x) => x.code === q.code);
  if (i >= 0) queues[i] = q; else queues.push(q);
  return q;
}

// ── 接入应用 · 投递 · 定义版本 ─────────────────────────────────────────────
const newSecret = () => `osk_live_${(Date.now() + ++secretSeq).toString(36)}${Math.random().toString(36).slice(2, 12)}`;

export const listApps = () => structuredClone(apps);

/** POST /accounts/{a}/apps：凭证只在这里返回一次 */
export function createApp(a: Omit<AppRow, "id" | "enabled">): { app: AppRow; secret: string } {
  requirePerm("flow:access:manage");
  const e: FieldError[] = [];
  if (!/^[A-Z][A-Z0-9_]{1,31}$/.test(a.code)) e.push({ path: "code", error: "代号 2–32 位，大写字母开头" });
  if (apps.some((x) => x.code === a.code)) e.push({ path: "code", error: "代号已存在" });
  if (!a.name.trim()) e.push({ path: "name", error: "名称必填" });
  if (!/^https:\/\/[^\s]+$/.test(a.callbackUrl)) e.push({ path: "callbackUrl", error: "回调地址必须是 https" });
  if (e.length) throw new FlowValidationError(`保存被拒：有 ${e.length} 处需要修改`, e);
  const app = { ...a, id: `app_${a.code.toLowerCase()}`, enabled: true };
  apps.push(app);
  return { app, secret: newSecret() };
}

/** POST /apps/{id}/rotate-credential：旧凭证立即失效，新凭证只返回一次 */
export function rotateCredential(id: string): { secret: string } {
  requirePerm("flow:access:manage");
  if (!apps.some((a) => a.id === id)) notFound("应用", "App", id);
  return { secret: newSecret() };
}

export const listDeliveries = () => [...deliveries].sort((a, b) => b.atMs - a.atMs);

/** POST /webhook-deliveries/{id}/redeliver：只有失败的能重投 */
export function redeliver(id: string, now: number = Date.now()): DeliveryRow {
  requirePerm("flow:access:manage");
  const d = deliveries.find((x) => x.id === id);
  if (!d) notFound("投递", "Delivery", id);
  if (d.status !== "FAILED") fail("只有投递失败的才能手动重投", "Only failed deliveries can be redelivered");
  Object.assign(d, { status: "DELIVERED", httpStatus: 200, durationMs: 92, error: undefined, attempts: d.attempts + 1, atMs: now });
  return d;
}

export function definitionVersions(): DefinitionVersionRow[] {
  return [
    ...processes.map((p) => {
      const v = currentVersion(p);
      return { kind: "APPROVAL_TEMPLATE" as const, code: p.def.code, version: v.version, publishedAtMs: v.atMs, publishedBy: v.by, channel: v.channel };
    }),
    ...otherDefs,
  ].sort((a, b) => b.publishedAtMs - a.publishedAtMs);
}

