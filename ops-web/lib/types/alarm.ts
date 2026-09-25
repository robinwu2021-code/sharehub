// 覆盖范围：告警治理四件套（设备运营域 · P1，对标简电云 A1~A4）——
// 告警记录 / 通知流水 / 告警码字典 / 通知规则。

// 告警等级：提示 / 警告 / 严重
import type { Archivable } from "./common";
import type { WorkOrderPriority } from "./workorder";

export type AlarmLevel = "INFO" | "WARN" | "CRITICAL";

// 告警记录：多厂商错误码归一化 —— alarmCode 是平台统一码，vendorErrorCode 是厂商原始码。
/**
 * 告警来源。**具名而不是内联联合**：两端同名词表比对
 * （后端 StatusVocabularyAcrossEndsTest）只认具名 `export type`。
 */
export type AlarmSource = "DEVICE" | "OTA" | "RENT"
  // 2026-09-25 业务告警：判定引擎（AlarmEngine）产出的行，source 写 EVAL。后端无同名枚举（是列注释词表）
  | "EVAL";

/**
 * 告警状态。
 *
 * **此前是内联在 interface 里的联合**（`status: "OPEN" | …`）——
 * 后端有 `AlarmStatus` 枚举，而 `StatusVocabularyAcrossEndsTest` 只认具名
 * `export type`，于是整个告警域的词表两端从未被比对过。
 * 与后端同名同值，改一边另一边会红。
 *
 * `CLOSED` 由 2026-09-25 新增的关闭动作走到（`POST /records/{alarmNo}/close`，
 * 权限码 `workorder:alarm:close`，必填关闭原因）。
 * 在那之前它**谁也走不到** —— 后端两条 CLOSE 边有定义、没人发事件。
 */
export type AlarmStatus = "OPEN" | "ACKED" | "CLOSED";

/** 告警上的运营动作。见 {@link ALARM_TRANSITIONS}。 */
export type AlarmAction = "ack" | "close";

/**
 * 关闭原因。与后端 `AlarmCloseReason` 一字不差。
 *
 * **关闭必填原因**，与「验收关单必须给结论」同一口径。实际理由：
 * 「误报率」这个数只有在关闭时记了原因才算得出来 —— 不记就只知道
 * 「这个月关了 300 条」，不知道其中多少是设备真故障、多少是规则太敏感。
 * 规则调不动，告警就会一直吵，吵到没人看。
 */
export type AlarmCloseReason = "RESOLVED" | "FALSE_ALARM" | "SELF_HEALED"
  // 2026-09-25 业务告警：以下两档只由系统写（自愈动作成功 / 被上层告警取代），人工关闭不可选，故不进 ALARM_CLOSE_REASONS
  | "AUTO_FIXED" | "SUPERSEDED";

export const ALARM_CLOSE_REASONS: { value: AlarmCloseReason; label: string; hint: string }[] = [
  { value: "RESOLVED", label: "已解决", hint: "设备侧问题已处理，通常伴随一张完工的工单" },
  { value: "FALSE_ALARM", label: "误报", hint: "设备其实没问题，是规则或阈值太敏感——这一档是调规则的依据" },
  { value: "SELF_HEALED", label: "已自愈", hint: "再次上报时已恢复，无需人工处理" },
];

/**
 * 告警状态机（SSOT）：页面按钮可用性与 mock 校验共用同一份，
 * 与工单 `WO_TRANSITIONS`、提现 `WITHDRAW_TRANSITIONS` 同一套写法。
 *
 * **只有一条边**，因为后端目前只有一个可走的动作（见 `AlarmStatus` 的 ⚠️）。
 * 一条边也值得建表：此前 `alarms/page.tsx` 手写 `a.status === "OPEN"`，
 * 后端加边时没有任何东西会提醒那一处。
 */
export const ALARM_TRANSITIONS: Record<AlarmAction,
  { from: readonly AlarmStatus[]; to: AlarmStatus; label: string }> = {
  ack: { from: ["OPEN"], to: "ACKED", label: "确认" },
  // 2026-09-25 补：此前这张表只有 ack，因为**后端也走不到 CLOSED** ——
  // AlarmStateMachine 两条 CLOSE 边俱全，而没有一处发这个事件。
  // 告警于是只能 OPEN→ACKED 然后停住，ACKED 行只增不减。
  close: { from: ["OPEN", "ACKED"], to: "CLOSED", label: "关闭" },
};

export const canAlarmAction = (status: AlarmStatus, action: AlarmAction) =>
  ALARM_TRANSITIONS[action].from.includes(status);

/** 当前状态下可执行的告警动作（列表操作列据此生成）。 */
export const alarmActions = (status: AlarmStatus): AlarmAction[] =>
  (Object.keys(ALARM_TRANSITIONS) as AlarmAction[]).filter((a) => canAlarmAction(status, a));

export interface AlarmRecord {
  alarmNo: string;
  /** 业务告警的主体不一定是柜（站点 / 订单 / 合同……），此时为 null。 */
  cabinetNo: string | null;
  /** 站点编号。只有 siteName 时同名站点连不准（同合同「按编号连」的理由）。 */
  siteNo: string | null;
  /** 后端业务告警行不回填站点名（null）；展示以 siteNo 的 RefLink 为准。 */
  siteName: string | null;
  /**
   * 站点归属的代理商编号；直营站点为 null。
   *
   * 告警要派给谁修，取决于这个站是谁在运营 —— 只有站点名时，值班得先去站点档案
   * 查一次归属。代理门户按自己的 agentNo 收敛数据，也靠它。
   */
  agentNo: string | null;
  /**
   * 告警从哪条路来的。三者排查路径完全不同：
   * `DEVICE` 设备自己上报 · `OTA` 固件投放过程中产生 · `RENT` 租借流程中判定。
   * 只看告警码时，一条 OTA 期间的批量告警和设备真故障长得一样。
   */
  source: AlarmSource;
  vendorCode: string | null; // 设备厂商（cd-tech / sd-power / chargenow）；业务告警为 null
  alarmCode: string; // 平台统一告警码，如 SLOT_STUCK
  vendorErrorCode: string | null; // 厂商原始错误码，各家风格不同（E203 / ERR-17 / 0x1F04）；业务告警为 null
  level: AlarmLevel;
  occurredAt: string;
  status: AlarmStatus;
  workOrderNo: string | null; // 关联工单号（转工单后回填）
  /**
   * 同源重复告警的合并次数（后端按 dedupKey 合并计数）。
   *
   * 「重复了 47 次」与「1 次」是噪音与火情的区别 —— 不显示它，
   * 列表里两者长得一模一样，值班的人无从排优先级。
   */
  count: number;
  /**
   * 去重键（业务告警形如 `SITE_UNRENTABLE:SITE:ST001`）。同源重复只累加 count，不另起一行。
   * 2026-09-25 起接入：业务告警的去重键就是「码 + 主体」，详情里展示它能直接回答
   * 「为什么这次没新开一条」—— 以前它只是内部实现细节，现在是运营排障要看的东西。
   */
  dedupKey: string | null;
  remark: string | null;
  /** 关闭原因；未关闭为 null。关闭时必填，见 {@link ALARM_CLOSE_REASONS}。 */
  closeReason: AlarmCloseReason | null;
  /** 关闭备注：原因之外的补充。 */
  closeNote: string | null;
  closedBy: string | null;
  closedAt: string | null;
  /** 业务告警维度；存量设备告警为 null（后端 `AlarmDtos.BusinessInfo`）。 */
  business: AlarmBusinessInfo | null;
}

// 确认告警结果：回带落库后的状态，前端不自己猜 —— 状态迁移由后端状态机裁决（OPEN → ACKED）
export interface AlarmAckResult {
  alarmNo: string;
  status: AlarmRecord["status"];
}

/**
 * 告警转工单结果，镜像后端 `AlarmDtos.WorkOrderRef`。
 *
 * 此前契约把这个方法声明成返回整行 `AlarmRecord`、页面读 `r.workOrderNo` ——
 * 后端从来返回的是这个三字段对象，接真后端时 toast 会显示「已转工单 undefined」。
 * `created` 是幂等结果标志：该端点以 alarmNo 为幂等键，重复调用返回首次的 woNo 且 created=false，
 * 不回带它就无法区分「新建了一张单」与「已经有单了」，运营会重复派人到现场。
 */
export interface AlarmWorkOrderRef {
  alarmNo: string;
  woNo: string;
  created: boolean;
}

/** 自动开工单的执行结果。回带明细而不只回条数 —— 运营要能核对「到底给哪几条开了单」。 */
export interface AutoWorkOrderResult {
  /** 命中「自动开工单」告警码且未关闭的告警数 */
  eligible: number;
  /** 本次真正新建的工单（告警号 → 工单号） */
  created: { alarmNo: string; woNo: string }[];
  /** 已有工单被跳过的条数（幂等命中） */
  skipped: number;
}

// 告警通知：触达流水（谁/何时/何渠道/成功失败）
/** 与后端 `AlarmNoticeStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type AlarmNoticeStatus = "SENT" | "FAILED";
export interface AlarmNotice {
  noticeNo: string;
  alarmNo: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WEBHOOK";
  target: string; // 接收人（手机号/邮箱/工号/回调地址）
  sentAt: string;
  status: AlarmNoticeStatus;
  failReason: string | null;
  /**
   * 本条对外真发时用的幂等键（历史流水为 null：seed 里没有重发链）。
   * 落库而不只是当请求头，是为了「这条到底是哪次点击发出去的」可查 —— 排重复计费的账要看得见键。
   */
  idempotencyKey: string | null;
  resendOf: string | null; // 非空 = 本条是某条失败通知的补发，指向原通知号
}

/**
 * 重发告警通知的入参。**幂等键必带**（拍板 #6）：重发是「真的再发一条短信/邮件」，
 * 重复提交＝重复触达 + 重复计费，故键由前端在点确认的瞬间生成、服务端按键拒绝第二次。
 */
export interface AlarmNoticeResendPayload {
  idempotencyKey: string;
}

// 告警代码字典：比竞品多「建议处置」「是否自动开工单」——字典即处置预案
export interface AlarmCode extends Archivable {
  code: string;
  message: string;
  /**
   * 英 / 阿业务名称。**只声明、页面暂不按 locale 取**：「记录自带的多语言字段怎么回落」
   * 仓里还没有统一口径（TDD-国际化i18n §6 P3），先各页自己选会出现两套回落规则。
   */
  messageEn: string | null;
  messageAr: string | null;
  level: AlarmLevel;
  suggestion: string; // 建议处置
  autoWorkOrder: boolean; // 命中后是否自动开工单
  /** 业务告警码的判定与处置配置；设备码为 null（后端 `AlarmDtos.BusinessCode`）。 */
  business: AlarmBusinessCode | null;
}

// 通知规则：比竞品多「静默窗口」「升级策略」——防夜间轰炸与告警风暴
/** 与后端 `AlarmRuleStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type AlarmRuleStatus = "ACTIVE" | "INACTIVE";
export interface AlarmRule extends Archivable {
  ruleNo: string;
  alarmCode: string;
  target: string; // 通知目标（角色/人/群）
  channel: AlarmNotice["channel"];
  method: "INSTANT" | "DIGEST"; // 即时 / 汇总
  quietStart: string; // 静默窗口起（HH:mm）
  quietEnd: string; // 静默窗口止（HH:mm）
  escalateMinutes: number; // N 分钟未处理则升级（0=不升级）
  status: AlarmRuleStatus;
}

// ─────────────────────────────────────────────────────────────
// 业务告警与告警待办（`/api/ops/alarms/**`、`/api/ops/alarm-todos`）
// ─────────────────────────────────────────────────────────────

/**
 * 业务告警域（SSOT，与后端 `AlarmDomain` 同名同值）。
 *
 * <p>2026-09-25 裁决 #3：**设备错误码降为「信号」，告警中心只放业务告警**。
 * 这九个域回答的是「用户/生意受了什么影响」，而不是「哪个零件坏了」——
 * 「E001 仓位卡阻」对运营没有意义，「这个站点还不了」才有。
 */
export type AlarmDomain =
  | "AVAILABILITY"   // 借不到
  | "RETURNABILITY"  // 还不了
  | "TRANSACTION"    // 交易异常（付了款没拿到宝…）
  | "FUND"           // 资金
  | "REVENUE"        // 收入
  | "ASSET"          // 资产
  | "PARTNER"        // 合作方（无合同营业…）
  | "SERVICE"        // 服务
  | "SAFETY";        // 安全

/** 告警主体类型（与后端 `AlarmSubjectType` 同名同值）。 */
export type AlarmSubjectType =
  | "SITE" | "CABINET" | "SLOT" | "POWERBANK" | "ORDER" | "USER"
  | "AGENT" | "PAYEE" | "CONTRACT" | "PAYMENT" | "WORK_ORDER";

/** 成因（与后端 `AlarmCause` 同名同值）。同一个域可由多种成因触发。 */
export type AlarmCause =
  | "OFFLINE" | "UNSTABLE" | "NO_STOCK" | "FULL" | "FAULT" | "LOW_BATTERY"
  | "OVERHEAT" | "HAZARD" | "AGED" | "MISSING" | "MIXED" | "SN_SEEN"
  | "NO_CONTRACT" | "EXPIRING" | "LOW_YIELD" | "SLA_BELOW" | "CANCEL_FAILED";

/**
 * 处置方式（与后端 `AlarmDisposition` 同名同值）。
 *
 * <p>**告警不是终点，处置才是**。一条告警最终要落到五者之一：
 * 自愈、开工单、转客服、只挂待办、仅通知。没有处置的告警只会堆着，
 * 堆到没人看 —— 而那时真正要紧的那条也一起被埋了。
 *
 * <p>`TODO` 与 `NOTIFY` 的差别：前者**要人办**（进待办、有人认领、能查办结率），
 * 后者只是告知（不产生任何人的工作）。把该办的事发成通知，就等于没人负责。
 *
 * <p>⚠️ 这个 `TODO` 差点漏掉：提取后端枚举时我用 `grep -v "^TODO$"` 滤注释噪音，
 * **把一个真常量一起滤掉了**，跨端词表卡口当场报「后端独有=[TODO]」。
 * 滤噪音的规则会连真值一起吃，这类漏配只有卡口抓得到。
 */
export type AlarmDisposition = "AUTO_FIX" | "WORK_ORDER" | "CS_CASE" | "TODO" | "NOTIFY";

/** 影响面（与后端 `ImpactScope` 同名同值）：优先级按它与时段算。 */
export type ImpactScope = "SITE" | "CABINET" | "SLOT" | "ORDER" | "ENTITY";

/** 影响时段（与后端 `ImpactPeriod` 同名同值）。高峰期同样的故障影响大得多。 */
export type ImpactPeriod = "PEAK" | "OPEN" | "CLOSED";

/** 判定方式（与后端 `AlarmEvalType` 同名同值）：事件即成立 / 持续 N 分钟 / 窗口内 N 次 / 周期指标。 */
export type AlarmEvalType = "EVENT" | "STATE" | "COUNT" | "METRIC";

/** 并单范围（与后端 `MergeScope` 同名同值）：同柜 / 同站 / 同区域的同类工单合并。 */
export type MergeScope = "DEVICE" | "SITE" | "REGION";

/** 恢复规则（与后端 `RecoverRule` 同名同值）。`DISPOSITION_DONE` = 只能随处置完成关闭。 */
export type RecoverRule = "SIGNAL_CLEAR" | "DISPOSITION_DONE" | "NONE";

/**
 * 业务告警维度（后端 `AlarmDtos.BusinessInfo`，挂在 `AlarmRecord.business`）。
 *
 * <p>`priority` 是**处置优先级**（工单词表 LOW…URGENT），与告警等级 `level` 是两套词表：
 * 等级说「这件事本身多严重」，处置优先级说「现在该多快去办」—— 同一条「站点借不到」
 * 在午间高峰的 A 级站和凌晨的 C 级站，等级相同、优先级不同。
 */
export interface AlarmBusinessInfo {
  domain: AlarmDomain | null;
  subjectType: AlarmSubjectType | null;
  /** 主体编号：站点号 / 柜号 / `柜号#仓位` / 订单号 / 合同号…… 按 subjectType 解读。 */
  subjectNo: string | null;
  cause: AlarmCause | null;
  priority: WorkOrderPriority | null;
  impactScope: ImpactScope | null;
  impactPeriod: ImpactPeriod | null;
  /** 站点分级（A/B/C），参与优先级加成。 */
  siteTier: string | null;
  /** 在途订单数：「3 位用户在途」比任何等级都更能说明这条要不要马上办。 */
  inFlightOrders: number | null;
  dispositionType: AlarmDisposition | null;
  /** 处置产物号：工单号 / 客服单号 / 待办号；仅通知或自愈为 null。 */
  dispositionRef: string | null;
  firstOccurredAt: string | null;
  lastOccurredAt: string | null;
  /** 开单时刻（成立 + 开单延迟）：延迟内自愈的告警就不必派人跑一趟。 */
  dueAt: string | null;
  recoveredAt: string | null;
  /** 非空 = 被这条上层告警取代（柜级并入站点级），列表默认只显示顶层。 */
  parentAlarmNo: string | null;
}

/** 业务告警码的判定与处置配置（后端 `AlarmDtos.BusinessCode`）。码即处置预案。 */
export interface AlarmBusinessCode {
  domain: AlarmDomain | null;
  subjectType: AlarmSubjectType | null;
  evalType: AlarmEvalType | null;
  holdMinutes: number | null;
  windowMinutes: number | null;
  threshold: number | null;
  businessHoursOnly: boolean;
  basePriority: WorkOrderPriority | null;
  impactAdjust: boolean;
  disposition: AlarmDisposition | null;
  ownerRole: string | null;
  woDelayMinutes: number | null;
  mergeScope: MergeScope | null;
  recoverRule: RecoverRule | null;
  recoverHoldMinutes: number | null;
  supersedes: string | null;
  enabled: boolean;
  /** 内置码：域 / 主体 / 判定方式 / 取代关系不可改（后端 beforeUpdate 强制回填）。 */
  builtin: boolean;
}

/** 告警时间线事件（后端 `dev_alarm_log.event` 写入值；列注释词表，无同名枚举）。 */
export type AlarmLogEvent =
  | "OPEN" | "DISPOSE" | "CLOSE" | "FIX_TRY" | "FIX_FAIL" | "IMPACT_UP"
  | "RECOVER" | "RELAPSE" | "SUPERSEDE";

/** 告警时间线一条（后端 `AlarmDtos.AlarmLogItem`）。 */
export interface AlarmLogItem {
  event: AlarmLogEvent;
  note: string | null;
  operator: string | null;
  at: string;
}

/**
 * 告警证据（`AlarmDetail.evidence` 解析后的一条）。后端把它存成 JSON 字符串原样回传，
 * 前端解析失败时按原文展示 —— 证据是排障的依据，解析不了也不能吞掉。
 */
export interface AlarmEvidence {
  signal: string;
  cabinetNo: string | null;
  slot: number | null;
  at: string | null;
  note: string | null;
}

/**
 * 告警详情（后端 `AlarmDtos.AlarmDetail`，`GET /records/{alarmNo}`）。
 *
 * <p>6a 的契约把它写成返回整行 `AlarmRecord` —— 真后端一直回的是这个包了一层的对象，
 * 接上就会读到 `record.record`。
 */
export interface AlarmDetail {
  record: AlarmRecord;
  /** 码的业务名称（如「站点借不到」）；码已删 / 未登记为 null。 */
  codeName: string | null;
  /** 码的处置预案（业务语言）。 */
  suggestion: string | null;
  /** 证据：JSON 数组字符串，见 {@link AlarmEvidence}；设备告警为 null。 */
  evidence: string | null;
  timeline: AlarmLogItem[];
  /** 同一主体近 7 天的其他告警。 */
  recentSameSubject: AlarmRecord[];
}

/**
 * 立即处置的结果（后端 `AlarmController#dispose` 的 Map 出参）。
 * `dispositionRef` 为 null = 自愈成功或仅通知，没有单可跳。**幂等**：已处置过返回首次的单号。
 */
export interface AlarmDisposeResult {
  alarmNo: string;
  dispositionRef: string | null;
}

/** 待办状态（与后端 `AlarmTodoStatus` 同名同值）。 */
export type AlarmTodoStatus = "OPEN" | "DONE" | "CANCELLED";

/**
 * 告警待办（后端 `AlarmTodo`）。
 *
 * <p>它解决的是「告警有人看但没人负责」：告警按 `roleCode` 落到岗位上，
 * 谁认领谁办完。没有这一层的话，一条告警在列表里躺着，
 * 每个人都以为别人会处理。
 */
export interface AlarmTodo {
  todoNo: string;
  alarmNo: string;
  alarmCode: string | null;
  /** 该办的岗位。与 assigneeNo 是「岗位兜底 + 个人认领」的关系。 */
  roleCode: string | null;
  assigneeNo: string | null;
  title: string;
  status: AlarmTodoStatus;
  siteNo: string | null;
  createdAt: string;
  doneAt: string | null;
  doneBy: string | null;
  doneNote: string | null;
}

/** 待办列表筛选（后端 `GET /api/ops/alarm-todos`）。`mine` 缺省 true：只看派给我 / 我这个岗位的。 */
export interface AlarmTodoQ {
  mine?: boolean;
  status?: AlarmTodoStatus;
  page?: number;
  size?: number;
}

/** 某个域的告警计数。 */
export interface AlarmDomainCount {
  open: number;
  critical: number;
}

/**
 * 告警摘要（后端 `AlarmSummary`）。
 *
 * <p>`disposedOpen` = **已处置但还没关闭**的数量，它是最容易被忽略的一格：
 * 开了工单不等于问题好了，工单没完工之前告警还在。
 * `autoRecoveredToday` 则是判断「规则是不是太敏感」的依据 —— 自愈率高说明在吵。
 */
export interface AlarmSummary {
  /** 只含有未关闭告警的域；缺席的域 = 0。存量设备告警（无域）后端计入 AVAILABILITY。 */
  byDomain: Partial<Record<AlarmDomain, AlarmDomainCount>>;
  disposedOpen: number;
  autoRecoveredToday: number;
}

/**
 * 处置预览（后端 `DispositionPreview`）。
 *
 * <p>**点「处置」之前先让人看见会发生什么**：开哪类工单、派给谁、会不会并进已有的单。
 * 不给预览的话，运营点下去才知道系统把单派给了错的人，而工单已经开出去了。
 */
export interface DispositionPreview {
  type: AlarmDisposition | null;
  woType: string | null;
  priority: WorkOrderPriority | null;
  assigneeType: string | null;
  assigneeNo: string | null;
  /** 非空 = 会并进这张已存在的工单，而不是新开一张。 */
  mergeIntoWoNo: string | null;
  todoRole: string | null;
  /** 兜底策略：主路径不可用时走什么。 */
  fallback: AlarmDisposition | null;
}

/** 路由表里的成因：具体成因，或 `"*"` = 该码的兜底路由（后端存库即 `*`）。 */
export type AlarmRouteCause = AlarmCause | "*";

/** 告警码 → 处置的路由规则（后端 `AlarmRoute`）。 */
export interface AlarmRoute {
  alarmCode: string;
  cause: AlarmRouteCause | null;
  disposition: AlarmDisposition | null;
  woType: string | null;
  /** 优先级增量（-2…+2 档，后端夹紧）：同一个码在不同成因下的紧急程度不同。 */
  priorityDelta: number | null;
  fallback: AlarmDisposition | null;
}

/** 保存路由的入参。`cause` 留空或 `"*"` 都存成兜底。 */
export interface AlarmRouteReq {
  cause?: AlarmRouteCause | null;
  disposition: AlarmDisposition;
  woType?: string | null;
  priorityDelta?: number | null;
  fallback?: AlarmDisposition | null;
}

/**
 * 每码统计（后端 `CodeStat`，近 N 天）。
 *
 * <p>三个比率是**调规则的依据**：误报率高 = 规则太敏感，自愈率高 = 不该开单，
 * 撤单率高 = 处置路由配错了。没有它们，「告警太吵」只能靠感觉说。
 */
export interface AlarmCodeStat {
  code: string;
  total: number;
  falseAlarmRate: number;
  selfHealRate: number;
  withdrawnRate: number;
}
