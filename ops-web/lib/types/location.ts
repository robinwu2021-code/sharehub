// 覆盖范围：场所域——场地方 → 站点 → 点位（ADR-013 两层）、合同、
// BD 线索（CRM）、站点经营分析、门店自助 onboarding、站点生命周期。

import type { Archivable } from "./common";

/**
 * 站点状态（SSOT）。与后端 `loc.SiteStatus`、`loc_site.status` 列注释三方同名同值。
 *
 * <p>2026-09-25 裁决：**站点状态与「门店生命周期」合并为一套**，
 * `loc_site_lifecycle` 降为变更日志。原先那套 `SITE_STAGES`
 * （PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED）**已删除** ——
 * 它与本状态只有 ACTIVE/CLOSED 偶然重名，两套并存过一段时间，
 * 界面上「阶段」与「状态」各说各话，没人说得清一个站点到底在哪。
 */
export type SiteStatus = "PREPARING" | "ACTIVE" | "PAUSED" | "WITHDRAWING" | "CLOSED";

/**
 * 站点状态机（SSOT）：页面按钮可用性与 mock 校验共用一份。
 *
 * <p>与合同不同，这**是**一张单向图：`PREPARING → ACTIVE` 由**首台设备上线**触发
 * （系统边，没有按钮）；撤场与关闭不可逆 —— 关了要重开就另建站点，
 * 否则同一个站点号的经营数据会跨两段互不相干的经营期，报表再也对不上。
 */
export const SITE_TRANSITIONS = {
  /** 系统边：首台设备上线时由后端推进，运营端不提供按钮。 */
  goLive: { from: ["PREPARING"] as SiteStatus[], to: "ACTIVE" as SiteStatus },
  pause: { from: ["ACTIVE"] as SiteStatus[], to: "PAUSED" as SiteStatus },
  resume: { from: ["PAUSED"] as SiteStatus[], to: "ACTIVE" as SiteStatus },
  withdraw: { from: ["ACTIVE", "PAUSED"] as SiteStatus[], to: "WITHDRAWING" as SiteStatus },
  close: { from: ["WITHDRAWING"] as SiteStatus[], to: "CLOSED" as SiteStatus },
} as const;

/**
 * 开业清单 / 关闭门禁的一条（后端 `Checklist.Item`）。
 *
 * <p>**未通过时必须给去处**：`fixHref` 指向能解决它的那个页面。
 * 只说「合同未生效」而不给链接，运营得自己猜去哪儿办 —— 门禁就成了拦路虎而非向导。
 */
export interface ChecklistItem {
  key: string;
  label: string;
  passed: boolean;
  detail: string | null;
  fixHref: string | null;
}

/** 门禁结果。`allPassed` 由服务端算，前端不要自己 every() —— 两处算法迟早不一致。 */
export interface Checklist {
  allPassed: boolean;
  items: ChecklistItem[];
}

/** 站点运营信息（后端 `SiteOps`）。列表不返回，详情才给。 */
export interface SiteOps {
  opsEmployeeNo: string | null;
  /** 运维代理（`loc_site_agent.role=OPERATE`）。与 opsEmployeeNo 是「二选一」：平台自营填前者。 */
  operateAgentNo: string | null;
  firstLiveAt: string | null;
  pauseReason: string | null;
  pauseUntil: string | null;
  withdrawReason: string | null;
  withdrawPlannedAt: string | null;
  closedAt: string | null;
  activeContractNo: string | null;
}

/**
 * 现场勘测结论（与后端 `loc.SurveyResult` 同名同值）。以站点**最近一次**勘测为准，
 * 开业清单里的「现场勘测」一项读它 —— 首台设备上线要求最近一次是 PASS。
 */
export type SurveyResult = "PASS" | "FAIL";

/**
 * 勘测时的现场信号强度（与后端 `loc.SignalLevel` 同名同值）。
 * NONE 时勘测不能判通过：没信号的柜子借不出也还不了。
 */
export type SignalLevel = "STRONG" | "GOOD" | "WEAK" | "NONE";

/** 一条勘测记录（后端 `SiteSurvey`）。只增不改：复勘就再记一条，历史留着对账。 */
export interface SiteSurvey {
  surveyNo: string;
  siteNo: string;
  signalLevel: SignalLevel;
  powerOk: boolean;
  placementNote: string | null;
  /** 现场照片（文件服务的 fileNo，用途 SURVEY_PHOTO）。 */
  fileNos: string[];
  result: SurveyResult;
  note: string | null;
  surveyedBy: string | null;
  surveyedAt: string;
}

/**
 * 记一次勘测（后端 `SurveyReq`）。服务端规则：PASS 要求信号不是 NONE 且能接电；
 * FAIL 必须写 note（不写原因，下一个去复勘的人不知道该看什么）。
 */
export interface SurveyReq {
  signalLevel: SignalLevel;
  powerOk: boolean;
  placementNote?: string;
  fileNos?: string[];
  result: SurveyResult;
  note?: string;
}

/** 站点状态流转留痕的一行。 */
export interface SiteStatusLogItem {
  event: string;
  fromStatus: SiteStatus | null;
  toStatus: SiteStatus | null;
  operator: string | null;
  reason: string | null;
  at: string;
}

/**
 * 站点摘要条（后端 `SiteSummary`）。
 *
 * <p>前五个是各状态计数，后两个是**要人动手的事**：缺运维责任人、缺营业时间。
 * 这两样缺了站点照常营业，所以没人会主动发现 —— 出事时才知道找不到人、也不知道几点开门。
 */
export interface SiteSummary {
  preparing: number;
  active: number;
  paused: number;
  withdrawing: number;
  closed: number;
  missingOwner: number;
  missingOpenHours: number;
}

export interface Site extends Archivable {
  siteNo: string;
  name: string;
  /**
   * 归属场地方编号（`venues.venueNo`）。**分成按它走**，不按 venueName ——
   * 种子里就有同名场地方，按名字连必然把钱分给另一家。
   * 2026-09-23：后端 `loc_site.venue_no` 列早就有、实体与 DTO 此前漏映射，已补。
   */
  venueNo?: string | null;
  venueName: string;
  agentNo: string | null; // 归属代理，空=平台直营
  /**
   * 以哪个品牌对 C 端呈现（`brands.brandNo`）。**一站一品牌是硬约束**（B1，2026-09-23）：
   * 分成、坪效、工单都按站点统计，一站两品牌会让「这笔钱算哪个品牌的」没有答案；
   * ADR-028 的取价也把品牌当过滤条件，一台设备必须能解出唯一品牌。
   */
  brandNo?: string | null;
  /** 区域字典 ID（`regions.regionId`，如 `DU-MAR`）。台账 M11：原先误存区域名。 */
  regionId: string;
  /** 区域展示名（冗余自 `regions.name`）。存 ID 是因为区域名会变、ID 不会；列表要展示故冗余一份。 */
  regionName: string;
  address: string;
  /** 经纬度（地图撒点用）。运营端按**站点**聚合展示，不逐台机柜撒点——上千机柜会卡。 */
  lat: number;
  lng: number;
  sceneType: string;
  /** 阿语站点名（后端 `loc_site.name_ar`）。空 = 阿语界面回退中文名。 */
  nameAr?: string;
  /** 营业时间，多段逗号分隔，如 `10:00-14:00,17:00-22:00`（后端 `loc_site.open_hours`）。 */
  openHours?: string;
  pointCount: number;
  cabinetCount: number;
  status: SiteStatus;
  /** 运维责任人（平台自营时的员工号）。代理运维走 `loc_site_agent.role=OPERATE`，不占本列。 */
  opsEmployeeNo?: string | null;
  /** 运营信息。列表不返回（只详情给），故可空。 */
  ops?: SiteOps | null;
}

/**
 * 站点坐标的可信窗口（SSOT）：抽屉里的提示文案与 mock 落库校验共用一份。
 *
 * 为什么要有窗口而不是只判 ±90/±180：经纬度写反（55, 25）在全球范围里完全合法，
 * 但会把点扔到印度洋，地图上表现为「站点凭空消失」，是最难查的一类脏数据。
 * 窗口 = 当前运营国家（UAE）的外接矩形，扩国家时改这里一处。
 */
export const SITE_COORD_BOUNDS = { latMin: 22, latMax: 27, lngMin: 51, lngMax: 57 } as const;

/**
 * 站点内的投放点位（Site → SitePoint → 机柜）。
 *
 * ⚠️ 原名 `Location`（2026-07-29 改名，台账 T8）。两个问题：
 *  1. **遮蔽 DOM 全局 `Location`** —— `.tsx` 里忘记 import 时会静默拿到 DOM 类型，tsc 不报错；
 *  2. 与 `Site`（站点）语义打架，读代码时分不清哪个是"场地"哪个是"点位"。
 * 业务号仍为 `locationNo`（后端字段名未动，改名只在前端类型层）。
 */
export interface SitePoint extends Archivable {
  locationNo: string; // 点位
  name: string;
  siteNo: string;
  siteName: string;
  spotDesc: string;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Venue extends Archivable {
  venueNo: string;
  name: string;
  contact: string;
  industry: string;
  locationCount: number;
}
/**
 * 进场合同状态（SSOT）。与后端 `loc.ContractStatus`、`loc_contract.status` 列注释三方同名同值。
 *
 * <p>2026-09-25 裁决：合同**走审批**。此前前端只有 `ACTIVE | EXPIRED` 两态，
 * 而后端六态 —— 差的那四个（DRAFT/PENDING/SIGNED/TERMINATED）在界面上会直接显示英文原值，
 * 按状态也筛不出来。跨端词表卡口没抓到它，是因为原先那是**内联联合**、配不上对
 * （见 `lib/types/inline-status-union.test.ts`）。
 */
export type ContractStatus = "DRAFT" | "PENDING" | "SIGNED" | "ACTIVE" | "EXPIRED" | "TERMINATED";

/**
 * 合同状态机（SSOT）：页面按钮可用性与 mock 校验共用一份。
 *
 * <p>`SIGNED → ACTIVE` 与 `ACTIVE → EXPIRED` 是**系统边**（按生效日 / 到期日由定时任务推进），
 * 没有按钮 —— 手工点「生效」会让合同的生效日与实际计费口径对不上。
 */
export const CONTRACT_TRANSITIONS = {
  submit: { from: ["DRAFT"] as ContractStatus[], to: "PENDING" as ContractStatus },
  withdraw: { from: ["PENDING"] as ContractStatus[], to: "DRAFT" as ContractStatus },
  approve: { from: ["PENDING"] as ContractStatus[], to: "SIGNED" as ContractStatus },
  reject: { from: ["PENDING"] as ContractStatus[], to: "DRAFT" as ContractStatus },
  /** 系统边：到生效日由 `contract-tick` 推进。 */
  activate: { from: ["SIGNED"] as ContractStatus[], to: "ACTIVE" as ContractStatus },
  /** 系统边：到期日由 `contract-tick` 推进。 */
  expire: { from: ["ACTIVE"] as ContractStatus[], to: "EXPIRED" as ContractStatus },
  terminate: { from: ["ACTIVE"] as ContractStatus[], to: "TERMINATED" as ContractStatus },
} as const;

/**
 * 合同分成模式（`loc_contract.share_mode` 列注释为真源）。
 *
 * <p>⚠️ **不要叫 `ShareMode`**：后端确实有个同名枚举，但那是
 * `share_rule.mode` 的**结算路径**（CHANNEL_SPLIT / LEDGER），与本词表毫无关系。
 * 同名会被跨端词表卡口配成一对，然后它会去强制两段本来无关的耦合 ——
 * 那比没覆盖更糟（见 `lib/types/inline-status-union.test.ts`）。
 */
export type ContractShareMode = "SHARE" | "ENTRY_FEE" | "GUARANTEE" | "FREE";

/** 分成基数：按净额还是毛额算。`loc_contract.share_base`。 */
export type ContractShareBase = "NET" | "GROSS";

/** 结算周期。`loc_contract.settle_period`。 */
export type ContractSettlePeriod = "MONTH" | "QUARTER";

/** 合同种类（与后端 `loc.ContractKind` 同名同值）。补充协议挂在主合同下。 */
export type ContractKind = "MAIN" | "SUPPLEMENT";

/**
 * 审批环节（与后端 `loc.ContractAuditStage` 同名同值）。
 *
 * <p>两段式：运营审条款 → 财务会签。**两段都在 PENDING 状态内** ——
 * 状态只说「在审批中」，是谁的活由本字段说。合并成一个状态的话，
 * 「待我审批」这个数就分不出运营和财务，两边互相等。
 */
export type ContractAuditStage = "OPS" | "FINANCE";

/** 提前终止申请的状态。`loc_contract.term_req_status`。 */
export type ContractTerminationStatus = "PENDING" | "APPROVED" | "REJECTED";

/** 合同条款（后端 `ContractTerms`）。与流程信息分开，避免 Contract 平铺二十个字段。 */
export interface ContractTerms {
  shareMode: ContractShareMode | null;
  shareBase: ContractShareBase | null;
  guaranteeAmount: number | null;
  currency: string | null;
  settlePeriod: ContractSettlePeriod | null;
  depositAmount: number | null;
  depositTerms: string | null;
  exclusive: boolean | null;
  deviceQuota: number | null;
  placementNote: string | null;
  autoRenew: boolean | null;
  signerName: string | null;
  remark: string | null;
}

/**
 * 提前终止申请（裁决 #4）。
 *
 * <p>**审批期间合同照常生效**，获批后到 `effectiveAt` 才由定时任务终止 ——
 * 提交申请就停止计费的话，审批被驳回时那几天的账没法补。
 */
export interface ContractTermination {
  status: ContractTerminationStatus;
  reason: string | null;
  effectiveAt: string | null;
  requestedBy: string | null;
  requestedAt: string | null;
  auditedBy: string | null;
  auditedAt: string | null;
  auditNote: string | null;
}

/** 流程信息（后端 `ContractFlow`）：谁在什么时候推进到了哪一步。 */
export interface ContractFlow {
  signedAt: string | null;
  submittedBy: string | null;
  submittedAt: string | null;
  auditedBy: string | null;
  auditedAt: string | null;
  auditNote: string | null;
  activatedAt: string | null;
  endedAt: string | null;
  endReason: string | null;
  prevContractNo: string | null;
  sourceLeadNo: string | null;
  contractKind: ContractKind | null;
  parentContractNo: string | null;
  auditStage: ContractAuditStage | null;
  financeAuditedBy: string | null;
  financeAuditedAt: string | null;
  financeAuditNote: string | null;
  termination: ContractTermination | null;
}

/**
 * 合同留痕事件（后端 `ContractServiceImpl.writeLog / transit` 写入的 event 值，逐字一致）。
 *
 * <p>审批四步各有自己的词：运营审 APPROVE / REJECT，财务会签 COSIGN / COSIGN_REJECT，
 * 终止申请 TERM_REQUEST → TERM_APPROVE（随后一条 TERMINATE 记状态变化）/ TERM_REJECT。
 * RENEW / SUPPLEMENT 记在**新生成的草稿**上；EXPIRE / TERMINATE 也可能由系统写（操作人为空）。
 */
export type ContractLogEvent =
  | "CREATE" | "UPDATE" | "SUBMIT" | "WITHDRAW" | "APPROVE" | "REJECT"
  | "COSIGN" | "COSIGN_REJECT" | "SIGN" | "ACTIVATE" | "EXPIRE" | "TERMINATE"
  | "RENEW" | "SUPPLEMENT" | "TERM_REQUEST" | "TERM_APPROVE" | "TERM_REJECT";

/** 合同流转留痕的一行（后端 `ContractLogItem`）。详情抽屉的时间线按它渲染。 */
export interface ContractLogItem {
  event: ContractLogEvent;
  fromStatus: ContractStatus | null;
  toStatus: ContractStatus | null;
  operator: string | null;
  note: string | null;
  at: string;
}

/**
 * 合同摘要条（后端 `ContractSummary`）。
 *
 * <p>六个数都是**要人动手的事**，不是统计口径：待我审批 / 待财务会签 /
 * 终止待审批 / 60 天内到期 / 已到期未续 / 缺签署件。
 * 放「合同总数」这类数字没有意义 —— 摘要条是待办入口，不是仪表盘。
 */
export interface ContractSummary {
  pendingMine: number;
  pendingCosign: number;
  terminationPending: number;
  expiring60: number;
  expiredNotRenewed: number;
  missingScan: number;
}

export interface Contract {
  contractNo: string;
  /**
   * 场地方 / 站点编号。合同是**场地方分成的唯一依据**，按编号连；
   * 名字只作展示冗余（同一商场不同楼层会有同名站点，按名字连必然连错）。
   * 后端 `loc_contract.venue_no/site_no` 列与实体都已有，读接口的 DTO 尚未带出（见 contracts/location.ts 缺口）。
   */
  venueNo?: string | null;
  siteNo?: string | null;
  venueName: string;
  siteName: string; // 合同绑定 场地方 × 站点（ADR-013）
  shareRate: number; // 0..1
  entryFee: number;
  startAt: string;
  endAt: string;
  status: ContractStatus;
  /** 合同扫描件。内嵌而非另开列表接口：一份合同的附件个数是个位数，单独分页没有意义。 */
  attachments: ContractAttachment[];
  /** 条款。列表不返回（只详情给），故可空。 */
  terms?: ContractTerms | null;
  /** 流程。列表不返回（只详情给），故可空。 */
  flow?: ContractFlow | null;
  /** 距到期天数。负数 = 已过期。由服务端算，前端不要自己按 endAt 减 —— 时区会差一天。 */
  remainingDays?: number | null;
}

/**
 * 合同附件（扫描件）元数据（后端 `ContractAttachment`）。
 *
 * <p>2026-09-25 起接入文件服务：先 `uploadFile(file, "CONTRACT_SCAN")` 拿到 fileNo，
 * 再 `addContractAttachment(no, { fileNos })` 挂上合同。下载地址按 fileNo 现取
 * （`fileUrl`，限时签名），**不存进附件行**——签名地址过期后留着只会是一个点不开的链接。
 */
export interface ContractAttachment {
  attachNo: string;
  fileName: string;
  size: number; // 字节；展示层再格式化
  uploadedBy: string;
  uploadedAt: string;
  /** 文件服务编号：取下载地址用。mock 早期假上传的种子没有它 —— 那几条没有「查看」入口。 */
  fileNo?: string | null;
  contentType?: string | null;
  /** 能否在抽屉里直接预览（图片可以，PDF 走下载）。由后端按类型判定。 */
  previewable?: boolean;
}
/**
 * 附件限制（SSOT，mock 落库校验用）。页面的 accept 与提示走 `FILE_CATEGORY_RULES.CONTRACT_SCAN`，
 * 那是文件服务的约束镜像 —— 真后端下上传就被它挡，这里的数与它保持一致。
 */
export const ATTACH_EXTS = ["pdf", "jpg", "jpeg", "png"] as const;
export const ATTACH_MAX_SIZE = 20 * 1024 * 1024;

/** 挂附件入参（后端 `AttachReq`）：已上传文件的 fileNo 列表。上传人由服务端取当前登录人。 */
export interface ContractAttachmentReq {
  fileNos: string[];
}

// —— 场所 · BD 拓展 CRM（商机）——

/**
 * 商机阶段（SSOT）。与后端 `loc.ext.LeadStatus` 同名同值（跨端词表卡口按名字配对，故叫 Status）。
 *
 * <p>页面上一律叫「阶段」，代码里 {@link LeadStage} 是它的别名 —— 两个名字指同一套值。
 */
export type LeadStatus = "NEW" | "CONTACTED" | "NEGOTIATING" | "SIGNED" | "LOST";
/** 线索阶段取值域（SSOT）：页面徽标/筛选、mock 校验、跟进记录的阶段快照共用一份。 */
export const LEAD_STAGES: readonly LeadStatus[] = ["NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST"];
export type LeadStage = LeadStatus;

/** 商机动作（后端 `LeadStateMachine` 的事件名，小驼峰化）。 */
export type LeadAction = "contact" | "negotiate" | "stepBack" | "sign" | "lose" | "reactivate";

/**
 * 商机阶段迁移表（SSOT）—— 逐边照抄后端 `LeadStateMachine`，跨端边卡口两向比对。
 *
 * <p>顺序推进 NEW → CONTACTED → NEGOTIATING → SIGNED；在跟阶段都可 LOST（原因必填）；
 * LOST 可重新激活回 NEW；允许一步回退 NEGOTIATING → CONTACTED（谈崩了重新接触是日常）。
 * SIGNED 是终态：签下后的变化在合同上发生。
 *
 * <p>**阶段只经动作改**（规则 R1）：记跟进时顺带推进、标记丢单、签约转化。编辑表单里没有阶段。
 * `sign` 这条边由「签约转化」走（它同时生成场地方 / 站点 / 合同草稿），不单独出按钮。
 */
export const LEAD_TRANSITIONS: Record<LeadAction, { from: readonly LeadStatus[]; to: LeadStatus }> = {
  contact: { from: ["NEW"], to: "CONTACTED" },
  negotiate: { from: ["CONTACTED"], to: "NEGOTIATING" },
  stepBack: { from: ["NEGOTIATING"], to: "CONTACTED" },
  sign: { from: ["NEGOTIATING"], to: "SIGNED" },
  lose: { from: ["NEW", "CONTACTED", "NEGOTIATING"], to: "LOST" },
  reactivate: { from: ["LOST"], to: "NEW" },
};

/** from → to 是否是一条合法迁移（同阶段 = 未推进，放行）。页面下拉与 mock 校验共用。 */
export function leadStageMoveOk(from: LeadStatus, to: LeadStatus): boolean {
  if (from === to) return true;
  return Object.values(LEAD_TRANSITIONS).some((t) => t.to === to && t.from.includes(from));
}

/** 从 from 能去的阶段（不含自身）。记跟进时「顺带推进」的下拉只给这些。 */
export function leadNextStages(from: LeadStatus): LeadStatus[] {
  return LEAD_STAGES.filter((s) => s !== from && leadStageMoveOk(from, s));
}

/**
 * 商机归属方类型（ADR-027 §五 / V55）。决定 `owner` 里那个号属于哪个命名空间。
 *
 * 判错的后果是拓展佣金算给不存在的人，或者白付一笔给自己的员工 —— 两种都不报错。
 */
export const LEAD_OWNER_TYPES = ["STAFF", "AGENT"] as const;
export type LeadOwnerType = (typeof LEAD_OWNER_TYPES)[number];

/** 商机上谈下来的条款（后端 `LeadTerms`）。签约转化时带进合同草稿，不必再录一遍。 */
export interface LeadTerms {
  shareMode: ContractShareMode | null;
  shareRate: number | null;
  entryFee: number | null;
  guaranteeAmount: number | null;
  termMonths: number | null;
  exclusive: boolean | null;
}

export interface Lead {
  leadNo: string;
  venueName: string;
  contact: string | null;
  stage: LeadStage;
  /** 归属方业务号：`ownerType=STAFF` 时是 employeeNo，`AGENT` 时是 agentNo。在线索池里时为空。 */
  owner: string | null;
  /** 见 LEAD_OWNER_TYPES。缺省 STAFF（这一列出现之前只可能是员工）。 */
  ownerType?: LeadOwnerType;
  /**
   * 这条商机最终落成的站点。
   *
   * 拓展归因只有落到站点上才能变成钱 —— 责任行挂在「伙伴 × 站点」上，
   * 而商机谈的是场地、站点是之后才建的。签下且归属是伙伴时，据此写 DEVELOP 责任行。
   */
  siteNo?: string | null;
  expectSites: number;
  /**
   * 下次跟进日（`YYYY-MM-DD`），空 = 未约。
   *
   * BD CRM 的核心作业字段 ——「今天该打谁的电话」靠它排。
   */
  nextFollowAt?: string | null;
  updatedAt: string;
  /** 场地地址。查重按「场地名或地址」任一相同判（后端 `lead.dedup.days` 天内）。 */
  address?: string | null;
  /** 签约转化后回填：关联 / 新建的场地方。 */
  venueNo?: string | null;
  /** 签约转化后回填：生成的合同草稿。有值 = 已转化，不能再转。 */
  contractNo?: string | null;
  /** 丢单原因（迁到 LOST 时必填；重新激活后保留作历史）。 */
  lostReason?: string | null;
  lostAt?: string | null;
  /**
   * 最后一次跟进时间（服务端维护）。**提醒与回收都按它算**：超 N 天未跟进提醒，
   * 超 M 天回收进公共线索池。列表的「最后跟进」读它，不读 updatedAt（改个联系人也会动后者）。
   */
  lastFollowAt?: string | null;
  /** 在公共线索池里（没有负责人，谁都能认领）。池里的商机要先认领才能跟进。 */
  inPool?: boolean;
  /** 被回收进池之前的负责人 —— 认领时让人知道「这条以前是谁在跟」。 */
  prevOwner?: string | null;
  /** 丢给了哪家竞品。 */
  competitorName?: string | null;
  /**
   * 竞品独家到期日。到期前 N 天由定时任务把 LOST 的商机**自动重新激活**回 NEW ——
   * 所以丢单时填上它，是给半年后的自己留一个提醒。
   */
  competitorExclusiveUntil?: string | null;
  reactivatedAt?: string | null;
  terms?: LeadTerms | null;
}

/**
 * 商机保存入参。后端收的是 `LocLead` 实体：谈判条款是**平铺**的（出参里收成 `terms`），
 * 独家叫 `exclusiveFlag`。`stage` 只在新建时生效（补录历史商机可直接落在某阶段），
 * 编辑时改阶段走动作 —— 表单里不放它（R1）。
 */
export interface LeadSaveReq {
  leadNo?: string;
  venueName?: string;
  contact?: string | null;
  address?: string | null;
  regionId?: string | null;
  stage?: LeadStage;
  owner?: string | null;
  ownerType?: LeadOwnerType;
  siteNo?: string | null;
  expectSites?: number;
  nextFollowAt?: string | null;
  lostReason?: string | null;
  competitorName?: string | null;
  competitorExclusiveUntil?: string | null;
  shareMode?: ContractShareMode | null;
  shareRate?: number | null;
  entryFee?: number | null;
  guaranteeAmount?: number | null;
  termMonths?: number | null;
  exclusiveFlag?: boolean | null;
}

/** 商机列表筛选。`inPool=true` 即「公共线索池」视图。 */
export interface LeadQ {
  page?: number;
  size?: number;
  keyword?: string;
  stage?: string;
  owner?: string;
  inPool?: boolean;
}

/**
 * 签约转化入参（后端 `LeadConvertReq`）。全部可空：空的从商机上取（场地名 / 地址 / 谈判条款）。
 * `venueNo` / `siteNo` 给了就关联已有的（站点须属于该场地方），不给就新建。
 */
export interface LeadConvertReq {
  venueNo?: string;
  siteNo?: string;
  siteName?: string;
  regionId?: string;
  address?: string;
  openHours?: string;
  shareMode?: ContractShareMode;
  shareRate?: number;
  entryFee?: number;
  guaranteeAmount?: number;
  startAt?: string;
  termMonths?: number;
  exclusive?: boolean;
}

/** 签约转化结果（后端 `LeadConversion`）：三个编号 + 各自是新建还是关联。 */
export interface LeadConversion {
  leadNo: string;
  venueNo: string;
  venueCreated: boolean;
  siteNo: string;
  siteCreated: boolean;
  contractNo: string;
}

/** 跟进方式。后端存字符串，取值域由前端定。 */
export const LEAD_FOLLOW_CHANNELS = ["CALL", "VISIT", "WHATSAPP", "EMAIL", "OTHER"] as const;
export type LeadFollowChannel = (typeof LEAD_FOLLOW_CHANNELS)[number];

/**
 * 线索跟进记录（append-only 流水，后端 `loc_lead_follow`）。
 *
 * 阶段快照记 `fromStage`/`toStage`：CRM 里真正要回答的问题是**哪一次跟进推动了阶段变化**。
 * ⚠️ 后端**每条都写 fromStage**（未推进时 from == to）；mock 旧数据首条为 null。
 * 判断「这一条推进了阶段」一律用 `fromStage && fromStage !== toStage`。
 */
export interface LeadFollowUp {
  followNo: string;
  leadNo: string;
  channel: LeadFollowChannel;
  fromStage: LeadStage | null;
  toStage: LeadStage;
  /** 记录人（服务端取当前登录人，入参里没有这一项）。 */
  owner: string | null;
  content: string;
  nextAt: string | null; // 下次跟进计划日（YYYY-MM-DD），空=未约
  createdAt: string;
}
/**
 * 记一条跟进（后端 `LeadFollowUpReq`）。
 *
 * `toStage` 不传 = 只留痕不动阶段；传了且与当前不同 = **同事务**推进阶段（须是合法迁移）。
 * 推到 LOST 时，本条内容就是丢单原因。记录人由服务端取当前登录人，不收 `owner`。
 */
export interface LeadFollowUpReq {
  content: string;
  channel: LeadFollowChannel;
  toStage?: LeadStage;
  nextAt?: string;
}
/**
 * 伙伴在某个站点承担的责任（ADR-027 §二）。
 *
 * 4 档而不是 ADR 原议的 5 档：效果管理（MANAGE）先并进 `OPERATE` —— 首批伙伴多半两件都做，
 * 分开只会让每站多配一行、每单多一条分润记录，而受益方与比例完全一样。
 * 要分时再加一档不迁移任何数据；反向（把合并过的拆回去）才要迁。
 */
export const SITE_AGENT_ROLES = ["INVEST", "DEVELOP", "OPERATE", "REFER"] as const;
export type SiteAgentRole = (typeof SITE_AGENT_ROLES)[number];

/**
 * 站点上的一行伙伴责任。
 *
 * **一行一责任**，撤销就是删这一行 —— 不用数组列：数组上撤销单个角色是读-改-写，
 * 两人同时改会互相覆盖，而覆盖的后果是有人多分了钱且不报错。
 */
export interface SiteAgent {
  /** 本表没有业务号（它是站点与伙伴之间的一条关系），故用 id。新增时为空。 */
  id?: number;
  siteNo: string;
  agentNo: string;
  /** 冗余展示名，出参才有；入参传了也不采信。 */
  agentName?: string | null;
  /** 伙伴的登记类型（A1），只作展示。 */
  agentType?: string | null;
  role: SiteAgentRole;
  /** 该责任对应的分润规则；空 = 用登记类型默认费率。 */
  ruleNo?: string | null;
  /**
   * 一次性对价（牵线费），签约时付；**仅 REFER 用**。
   *
   * `null` = 还没谈定，与 `0`（明确不付）不是一回事：前者该有人去配，
   * 后者是明确的不分。混为一谈就会静默少付。
   */
  oneOffAmount?: number | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
  /** 为什么是这个责任 —— 结算争议时的人话依据。 */
  remark?: string;
}

export interface SiteAnalysis {
  siteNo: string;
  siteName: string;
  revenue: number;
  orders: number;
  turnover: number; // 次/日
  paybackDays: number;
  cabinetCount: number;
  currency: string;
}

// —— 门店自助 Onboarding（场地域 · P2）——
export interface VenueOnboarding {
  onboardingNo: string;
  /**
   * 审核通过后建出的场地方编号。
   *
   * 这个字段**直到场地方写入口补齐后才真正有意义** —— 在那之前占位实现只取号不落行，
   * 号回填了却查无此人。现在它是从进件跳到场地方档案的唯一线索。
   */
  venueNo?: string | null;
  venueName: string;
  contact: string;
  industry: string;
  requestedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewAt: string | null;
  reviewNote: string | null;
}

// —— 门店生命周期（只读漏斗 · 2026-09-25 起降为「变更日志 + 视图」）——

/**
 * 漏斗一行：**签约前是商机，签约后是站点**。
 *
 * <p>原先这里是一套独立的 `SITE_STAGES` 六阶段，可任意互相推进（含 CHURNED→ACTIVE）。
 * 2026-09-25 裁决把它与站点状态合并：站点的「阶段」就是它的 {@link SiteStatus}，
 * 商机的阶段是 {@link LeadStage}，两段拼成一条从线索到闭店的漏斗。
 *
 * <p>**没有「推进阶段」这个动作了** —— 推进商机走 CRM 的跟进，推进站点走站点状态机
 * （暂停/恢复/撤场/关闭）。此前那个可进可退的阶段抽屉是第二套事实，
 * 改了它不影响站点真实状态，于是漏斗好看而数据不准。
 */
export interface LifecycleRow {
  /** `LEAD` = 商机（phase 是 {@link LeadStage}）· `SITE` = 站点（phase 是 {@link SiteStatus}）。 */
  kind: "LEAD" | "SITE";
  no: string;
  name: string;
  phase: LeadStage | SiteStatus;
  phaseSince: string | null;
  /** 在当前阶段停留天数。空 = 算不出（缺进入时刻）。 */
  daysInPhase: number | null;
  owner: string | null;
}

/**
 * 漏斗每一档（后端 `FunnelStage`）。`phase` 取值域同 {@link LifecycleRow.phase}。
 *
 * <p>**没有 label**：档位名由前端按 `kind + phase` 查映射表出（三语各自翻），
 * 后端给中文名只会让阿语界面冒出一格中文。`avgDaysInPhase` 空 = 该档没有对象。
 */
export interface FunnelStage {
  kind: LifecycleRow["kind"];
  phase: LeadStage | SiteStatus;
  count: number;
  avgDaysInPhase: number | null;
}
