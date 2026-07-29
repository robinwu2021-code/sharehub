// 覆盖范围：场所域（ADR-013）—— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。
import type { PageQ, ArchiveQ } from "../query";
import type {
  PageResult, Site, SitePoint, Venue, Contract, Lead, SiteAnalysis,
  VenueOnboarding, SiteLifecycle,
} from "../../types";

export interface LocationApi {
  listSites(q?: ArchiveQ): Promise<PageResult<Site>>;
  saveSite(s: Partial<Site> & { siteNo?: string }): Promise<Site>;
  listLocations(q?: ArchiveQ): Promise<PageResult<SitePoint>>;
  savePoint(l: Partial<SitePoint> & { locationNo?: string }): Promise<SitePoint>;
  listVenues(q?: ArchiveQ): Promise<PageResult<Venue>>;
  listContracts(q?: PageQ): Promise<PageResult<Contract>>;

  // === 场所扩展 tab ===
  listLeads(q?: PageQ): Promise<PageResult<Lead>>;
  listSiteAnalysis(q?: PageQ): Promise<PageResult<SiteAnalysis>>;
  saveLead(x: Partial<Lead> & { leadNo?: string }): Promise<Lead>;
  saveVenue(x: Partial<Venue> & { venueNo?: string }): Promise<Venue>;
  saveContract(x: Partial<Contract> & { contractNo?: string }): Promise<Contract>;

  // === 门店 Onboarding / 生命周期 ===
  listVenueOnboardings(q?: PageQ): Promise<PageResult<VenueOnboarding>>;
  saveVenueOnboarding(x: Partial<VenueOnboarding> & { onboardingNo?: string }): Promise<VenueOnboarding>;
  listSiteLifecycles(q?: PageQ): Promise<PageResult<SiteLifecycle>>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveSite(siteNo: string): Promise<Site>;
  unarchiveSite(siteNo: string): Promise<Site>;
  archivePoint(locationNo: string): Promise<SitePoint>;
  unarchivePoint(locationNo: string): Promise<SitePoint>;
  archiveVenue(venueNo: string): Promise<Venue>;
  unarchiveVenue(venueNo: string): Promise<Venue>;
}
