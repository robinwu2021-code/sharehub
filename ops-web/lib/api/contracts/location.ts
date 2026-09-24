// 覆盖范围：场所域（ADR-013）—— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。
import type { PageQ, ArchiveQ , ReportQ } from "../query";
import type {
  PageResult, Site, SitePoint, Venue, Contract, ContractAttachmentReq,
  Lead, LeadFollowUp, LeadFollowUpReq, SiteAnalysis,
  VenueOnboarding, SiteLifecycle, SiteStageChangeReq, SiteAgent,
} from "../../types";

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
  listContracts(q?: PageQ): Promise<PageResult<Contract>>;

  // === 场所扩展 tab ===
  listLeads(q?: PageQ): Promise<PageResult<Lead>>;
  /** 站点坪效。period 复用报表域的 ReportQ —— 同一套周期枚举，避免「近 30 日」两处含义不同。 */
  listSiteAnalysis(q?: ReportQ): Promise<PageResult<SiteAnalysis>>;
  saveLead(x: Partial<Lead> & { leadNo?: string }): Promise<Lead>;
  saveVenue(x: Partial<Venue> & { venueNo?: string }): Promise<Venue>;
  saveContract(x: Partial<Contract> & { contractNo?: string }): Promise<Contract>;

  /**
   * 线索跟进流水（BD CRM 时间线）。
   * ⚠️ **后端缺口**：`LocExtController` 只有 `/leads` 与 `/leads/{leadNo}`，跟进记录表与端点都还没有。
   */
  listLeadFollowUps(leadNo: string, q?: PageQ): Promise<PageResult<LeadFollowUp>>;
  /** 记一条跟进（可同时推进线索阶段）。⚠️ **后端缺口**，同上。 */
  addLeadFollowUp(leadNo: string, req: LeadFollowUpReq): Promise<LeadFollowUp>;

  /**
   * 合同扫描件上传（拍板点 #3：mock 阶段假上传，只登记文件名 + 大小）。返回整份合同，便于抽屉一次刷新。
   * ⚠️ **后端缺口**：合同侧后端目前只有 `GET /api/ops/contracts`，连 `POST /api/ops/contracts` 都没有，附件端点更没有。
   */
  addContractAttachment(contractNo: string, req: ContractAttachmentReq): Promise<Contract>;
  /** 移除误传的扫描件。⚠️ **后端缺口**，同上。 */
  removeContractAttachment(contractNo: string, attachNo: string): Promise<Contract>;

  // === 门店 Onboarding / 生命周期 ===
  /**
   * 进件审核。**审核是动作，不是改字段** —— 它有状态机（只有 PENDING 能审）、
   * 通过时要建出场地方并回填 `venueNo`、驳回必须给原因。
   * 走 `saveVenueOnboarding` 改 `status` 是改不动的：后端那条路径根本不受理状态。
   */
  reviewVenueOnboarding(onboardingNo: string, approve: boolean, note?: string): Promise<VenueOnboarding>;

  listVenueOnboardings(q?: PageQ): Promise<PageResult<VenueOnboarding>>;
  saveVenueOnboarding(x: Partial<VenueOnboarding> & { onboardingNo?: string }): Promise<VenueOnboarding>;
  listSiteLifecycles(q?: PageQ): Promise<PageResult<SiteLifecycle>>;
  /** 阶段流转（每次都留痕到 loc_site_lifecycle_log）。站点首次流转即建档，故 siteNo 可以还没有生命周期行。 */
  changeSiteStage(siteNo: string, req: SiteStageChangeReq): Promise<SiteLifecycle>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveSite(siteNo: string): Promise<Site>;
  unarchiveSite(siteNo: string): Promise<Site>;
  archivePoint(locationNo: string): Promise<SitePoint>;
  unarchivePoint(locationNo: string): Promise<SitePoint>;
  archiveVenue(venueNo: string): Promise<Venue>;
  unarchiveVenue(venueNo: string): Promise<Venue>;
}
