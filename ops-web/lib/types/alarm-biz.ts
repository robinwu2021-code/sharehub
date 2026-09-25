// 业务告警与告警待办（`/api/ops/alarms/**`、`/api/ops/alarm-todos`）。
//
// ⚠️ **为什么单独成文件而不是并进 alarm.ts**
// 2026-09-25 落这一批时，`lib/types/alarm.ts` 正被另一个会话改着（`git status` 长时间为 `M`）。
// 按 CLAUDE.md 的并发纪律，有别人在途改动时「改用非侵入方式」——
// 往那个文件里加内容，`git add` 会把对方未完成的改动一起提交。
// **这不是永久归宿**：等 `alarm.ts` 空出来应当并回去、本文件删除。
// 同款处理见 `lib/types/device-ops.ts`。

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
  byDomain: Record<string, AlarmDomainCount>;
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
  priority: string | null;
  assigneeType: string | null;
  assigneeNo: string | null;
  /** 非空 = 会并进这张已存在的工单，而不是新开一张。 */
  mergeIntoWoNo: string | null;
  todoRole: string | null;
  /** 兜底策略：主路径不可用时走什么。 */
  fallback: string | null;
}

/** 告警码 → 处置的路由规则（后端 `AlarmRoute`）。 */
export interface AlarmRoute {
  alarmCode: string;
  cause: AlarmCause | null;
  disposition: AlarmDisposition | null;
  woType: string | null;
  /** 优先级增量：同一个码在不同成因下的紧急程度不同。 */
  priorityDelta: number | null;
  fallback: string | null;
}

/** 保存路由的入参。 */
export interface AlarmRouteReq {
  cause?: AlarmCause | null;
  disposition: AlarmDisposition;
  woType?: string | null;
  priorityDelta?: number | null;
  fallback?: string | null;
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
