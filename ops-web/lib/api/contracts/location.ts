// 覆盖范围：场所域（ADR-013）—— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。
import type { PageQ, ArchiveQ , ReportQ } from "../query";
import type {
  PageResult, Site, SitePoint, Venue, Contract, ContractAttachmentReq,
  Lead, LeadQ, LeadSaveReq, LeadConvertReq, LeadConversion, LeadFollowUp, LeadFollowUpReq, SiteAnalysis,
  VenueOnboarding, LifecycleRow, FunnelStage, SiteAgent, ContractSummary, ContractLogItem,
  SiteSummary, SiteStatusLogItem, Checklist, SiteSurvey, SurveyReq,
} from "../../types";

/**
 * 合同列表筛选（后端 `ContractController.page`）。`pendingMine` = 摘要条「待我审批」那一格；
 * `endFrom/endTo` 为到期日区间（YYYY-MM-DD，含端点）。
 */
export type ContractQ = PageQ & {
  status?: string; venueNo?: string; siteNo?: string; endFrom?: string; endTo?: string; pendingMine?: boolean;
};

export interface LocationApi {
  /** 站点上的伙伴责任（ADR-027）。只有运营方能配——责任直接决定分钱。 */
  listSiteAgents(siteNo: string): Promise<SiteAgent[]>;
  saveSiteAgent(siteNo: string, x: Partial<SiteAgent>): Promise<SiteAgent>;
  /** 契约禁止 delete*，用 remove。撤销就是删这一行。 */
  removeSiteAgent(siteNo: string, id: number): Promise<{ ok: boolean }>;

  listSites(q?: ArchiveQ): Promise<PageResult<Site>>;
  saveSite(s: Partial<Site> & { siteNo?: string }): Promise<Site>;
  listLocations(q?: ArchiveQ): Promise<PageResult<SitePoint>>;
  savePoint(l: Partial<SitePoint> & { locationNo?: string }): Promise<SitePoint>;
  listVenues(q?: ArchiveQ): Promise<PageResult<Venue>>;
  listContracts(q?: ContractQ): Promise<PageResult<Contract>>;

  // === 场所扩展 tab ===
  /** 商机列表。`inPool=true` = 公共线索池视图（超 M 天无跟进被回收的商机，谁都能认领）。 */
  listLeads(q?: LeadQ): Promise<PageResult<Lead>>;
  /** 商机详情（深链 `/venues?tab=crm&no=` 直接打开时列表可能还没这一行）。 */
  getLead(leadNo: string): Promise<Lead>;
  /** 从公共线索池认领：认领人成为负责人并重新计时。已被别人认领时 409。 */
  claimLead(leadNo: string): Promise<Lead>;
  /**
   * 签约转化：场地方 + 筹备中站点 + 带谈判条款的合同草稿一次生成（不重复录入）。
   * 只有洽谈中（或已签未转化）的商机能转；已转化过的拒绝。
   */
  convertLead(leadNo: string, req?: LeadConvertReq): Promise<LeadConversion>;
  /** 站点坪效。period 复用报表域的 ReportQ —— 同一套周期枚举，避免「近 30 日」两处含义不同。 */
  listSiteAnalysis(q?: ReportQ): Promise<PageResult<SiteAnalysis>>;
  /**
   * 商机建档 / 编辑。新建时服务端**查重**（同场地名或地址、90 天内别人在跟 → 409 并说出是谁）；
   * 编辑时改阶段须是合法迁移 —— 但页面不从这里改阶段（R1），阶段走跟进 / 转化动作。
   */
  saveLead(x: LeadSaveReq): Promise<Lead>;
  saveVenue(x: Partial<Venue> & { venueNo?: string }): Promise<Venue>;
  saveContract(x: Partial<Contract> & { contractNo?: string }): Promise<Contract>;

  /** 线索跟进流水（BD CRM 时间线），新的在前。 */
  listLeadFollowUps(leadNo: string, q?: PageQ): Promise<PageResult<LeadFollowUp>>;
  /**
   * 记一条跟进，可**同事务**推进阶段（`toStage`）。推到 LOST 时内容即丢单原因。
   * 池里的商机不能跟进（先认领）。
   */
  addLeadFollowUp(leadNo: string, req: LeadFollowUpReq): Promise<LeadFollowUp>;

  /**
   * 挂合同扫描件：先 `uploadFile(file, "CONTRACT_SCAN")` 拿 fileNo，再按 fileNo 挂上。
   * 返回整份合同，抽屉一次刷新。已到期 / 已终止的合同不再收。
   */
  addContractAttachment(contractNo: string, req: ContractAttachmentReq): Promise<Contract>;
  /** 移除误传的扫描件（软删，留痕）。 */
  removeContractAttachment(contractNo: string, attachNo: string): Promise<Contract>;

  // ——— 合同审批（2026-09-25 裁决：进场合同走审批）———
  // 状态机见 CONTRACT_TRANSITIONS。**没有「直接生效」的口子**：
  // SIGNED→ACTIVE 与 ACTIVE→EXPIRED 是系统边，由 contract-tick 按生效日/到期日推进。

  /** 合同详情（含 terms / flow / remainingDays，列表不返回这三块）。 */
  getContract(contractNo: string): Promise<Contract>;
  /** 摘要条：六个都是要人动手的事，不是统计口径。 */
  contractSummary(): Promise<ContractSummary>;
  /** 流转留痕，详情抽屉的时间线。 */
  listContractLogs(contractNo: string): Promise<ContractLogItem[]>;

  /** 提交审批：DRAFT → PENDING（进运营环节）。 */
  submitContract(contractNo: string): Promise<Contract>;
  /** 撤回：PENDING → DRAFT。审批中反悔用它，不要去驳回自己的单。 */
  withdrawContract(contractNo: string, note?: string): Promise<Contract>;
  /** 运营审批。`result=REJECT` 时 reason 必填——驳回不说理由，提交人只能猜。 */
  auditContract(contractNo: string, result: "APPROVE" | "REJECT", reason?: string): Promise<Contract>;
  /** 财务会签（第二环节）。同样 REJECT 要理由。 */
  cosignContract(contractNo: string, result: "APPROVE" | "REJECT", reason?: string): Promise<Contract>;
  /** 签署：登记签署日与扫描件。签署件缺失是摘要条里单列的一项，别跳过。 */
  signContract(contractNo: string, signedAt: string, fileNos: string[]): Promise<Contract>;

  /** 申请提前终止。**审批期间合同照常生效**，获批后到 effectiveAt 才终止。 */
  terminateContract(contractNo: string, reason: string, effectiveAt?: string): Promise<Contract>;
  /** 终止申请的审批。 */
  auditContractTermination(contractNo: string, result: "APPROVE" | "REJECT", reason?: string): Promise<Contract>;

  /** 续签：按原合同生成新草稿（prevContractNo 指回原合同）。 */
  renewContract(contractNo: string): Promise<Contract>;
  /** 补充协议：只定生效日（缺省明天），条款在生成的草稿上改，到期日跟原合同。 */
  supplementContract(contractNo: string, startAt?: string): Promise<Contract>;

  // === 门店 Onboarding / 生命周期 ===
  /**
   * 进件审核。**审核是动作，不是改字段** —— 它有状态机（只有 PENDING 能审）、
   * 通过时要建出场地方并回填 `venueNo`、驳回必须给原因。
   * 走 `saveVenueOnboarding` 改 `status` 是改不动的：后端那条路径根本不受理状态。
   */
  reviewVenueOnboarding(onboardingNo: string, approve: boolean, note?: string): Promise<VenueOnboarding>;

  listVenueOnboardings(q?: PageQ): Promise<PageResult<VenueOnboarding>>;
  /** 进件详情。 */
  getVenueOnboarding(onboardingNo: string): Promise<VenueOnboarding>;
  saveVenueOnboarding(x: Partial<VenueOnboarding> & { onboardingNo?: string }): Promise<VenueOnboarding>;
  /**
   * 门店生命周期漏斗的明细行（商机 + 站点拼成一条）。**只读** ——
   * 2026-09-25 起没有「推进阶段」这个动作：推商机走 CRM 跟进，推站点走站点状态机。
   */
  listSiteLifecycles(q?: PageQ & { phase?: string }): Promise<PageResult<LifecycleRow>>;
  /** 漏斗每一档的计数。 */
  siteLifecycleFunnel(): Promise<FunnelStage[]>;

  // ——— 站点状态机与门禁（2026-09-25）———
  // 状态机见 SITE_TRANSITIONS。**没有 goLive** —— 首台设备上线由后端推进（系统边）。

  /** 站点详情（含 ops 子对象，列表不返回）。 */
  getSite(siteNo: string): Promise<Site>;
  /** 摘要条：五个状态计数 + 缺运维责任人 / 缺营业时间两项待办。 */
  siteSummary(): Promise<SiteSummary>;
  /** 状态流转留痕。 */
  listSiteStatusLogs(siteNo: string): Promise<SiteStatusLogItem[]>;

  /**
   * 开业清单：筹备中的站点还差什么才能营业。
   * 它是**向导不是拦路虎** —— 每条未通过都带 fixHref 指向能解决它的页面。
   */
  siteOpeningChecklist(siteNo: string): Promise<Checklist>;
  /** 关闭门禁：撤场中的站点还有什么没了结（在用设备、未结账款…）。 */
  siteCloseGate(siteNo: string): Promise<Checklist>;

  // 暂停 / 恢复**不在这里**：OperationApi 早就有 pauseSite / resumeSite，打的是同一对端点。
  // 在两个切片各定义一份，`Api` 组合根会直接编译不过（属性签名不一致），
  // 而就算签名碰巧一致，页面也会不知道该调哪个。pauseUntil 加在 OperationApi 那一份上。

  /**
   * 撤场：进入 WITHDRAWING，为每台在站机柜开撤机工单，完了再 close。
   * `plannedAt` **必填**且至少在今天 + N 天之后（服务端参数 `site.withdraw.lead_days`，默认 7）。
   */
  withdrawSite(siteNo: string, reason: string, plannedAt: string): Promise<Site>;
  /** 关闭：**不可逆**，说明必填。关了要重开只能另建站点，否则同一站点号跨两段经营期，报表对不上。 */
  closeSite(siteNo: string, note: string): Promise<Site>;

  // ——— 现场勘测（C1）———
  /** 站点的勘测记录，新的在前。以最近一次为准（开业清单读它）。 */
  listSiteSurveys(siteNo: string): Promise<SiteSurvey[]>;
  /** 记一次勘测。PASS 要求有信号且能接电；FAIL 必须写说明。照片先经 uploadFile（SURVEY_PHOTO）。 */
  recordSiteSurvey(siteNo: string, req: SurveyReq): Promise<SiteSurvey>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveSite(siteNo: string): Promise<Site>;
  unarchiveSite(siteNo: string): Promise<Site>;
  archivePoint(locationNo: string): Promise<SitePoint>;
  unarchivePoint(locationNo: string): Promise<SitePoint>;
  archiveVenue(venueNo: string): Promise<Venue>;
  unarchiveVenue(venueNo: string): Promise<Venue>;
}
