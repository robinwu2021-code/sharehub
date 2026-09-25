// 覆盖范围：场所域 —— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。端点前缀：/api/ops/**
import { client } from "../http-client";
import type { LocationApi, ContractQ } from "../contracts/location";
import type { LeadQ } from "../../types";
import type { PageQ, ArchiveQ , ReportQ } from "../query";

export const locationHttp: LocationApi = {
  listSiteAgents: (siteNo) => client.get(`/api/ops/sites/${siteNo}/agents`),
  saveSiteAgent: (siteNo, x) => client.post(`/api/ops/sites/${siteNo}/agents`, x),
  removeSiteAgent: (siteNo, id) => client.post(`/api/ops/sites/${siteNo}/agents/${id}/remove`, {}),
  listSites: (q?: ArchiveQ) => client.get("/api/ops/sites", q),
  saveSite: (s) => client.post(s.siteNo ? `/api/ops/sites/${s.siteNo}` : "/api/ops/sites", s),
  listLocations: (q?: ArchiveQ) => client.get("/api/ops/locations", q),
  savePoint: (l) => client.post(l.locationNo ? `/api/ops/locations/${l.locationNo}` : "/api/ops/locations", l),
  listVenues: (q?: ArchiveQ) => client.get("/api/ops/venues", q),
  listContracts: (q?: ContractQ) => client.get("/api/ops/contracts", q),

  // 场所扩展
  listLeads: (q?: LeadQ) => client.get("/api/ops/leads", q),
  getLead: (no) => client.get(`/api/ops/leads/${no}`),
  claimLead: (no) => client.post(`/api/ops/leads/${no}/claim`, {}),
  convertLead: (no, req) => client.post(`/api/ops/leads/${no}/convert`, req ?? {}),
  listSiteAnalysis: (q?: ReportQ) => client.get("/api/ops/site-analysis", q),
  saveLead: (x) => client.post(x.leadNo ? `/api/ops/leads/${x.leadNo}` : "/api/ops/leads", x),
  saveVenue: (x) => client.post(x.venueNo ? `/api/ops/venues/${x.venueNo}` : "/api/ops/venues", x),
  saveContract: (x) => client.post(x.contractNo ? `/api/ops/contracts/${x.contractNo}` : "/api/ops/contracts", x),

  // 跟进流水 / 合同附件（附件入参是 fileNos：先经文件服务上传）
  listLeadFollowUps: (leadNo, q?: PageQ) => client.get(`/api/ops/leads/${leadNo}/follow-ups`, q),
  addLeadFollowUp: (leadNo, req) => client.post(`/api/ops/leads/${leadNo}/follow-ups`, req),
  addContractAttachment: (contractNo, req) => client.post(`/api/ops/contracts/${contractNo}/attachments`, req),
  removeContractAttachment: (contractNo, attachNo) => client.post(`/api/ops/contracts/${contractNo}/attachments/${attachNo}/remove`, {}),

  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q?: PageQ) => client.get("/api/ops/venue-onboardings", q),
  getVenueOnboarding: (no) => client.get(`/api/ops/venue-onboardings/${no}`),
  saveVenueOnboarding: (x) => client.post(x.onboardingNo ? `/api/ops/venue-onboardings/${x.onboardingNo}` : "/api/ops/venue-onboardings", x),
  reviewVenueOnboarding: (onboardingNo, approve, note) =>
    client.post(`/api/ops/venue-onboardings/${onboardingNo}/review`, { approve, note }),
  listSiteLifecycles: (q?: PageQ & { phase?: string }) => client.get("/api/ops/site-lifecycles", q),
  siteLifecycleFunnel: () => client.get("/api/ops/site-lifecycles/funnel"),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveSite: (no) => client.post(`/api/ops/sites/${no}/archive`, {}),
  unarchiveSite: (no) => client.post(`/api/ops/sites/${no}/unarchive`, {}),
  archivePoint: (no) => client.post(`/api/ops/locations/${no}/archive`, {}),
  unarchivePoint: (no) => client.post(`/api/ops/locations/${no}/unarchive`, {}),
  archiveVenue: (no) => client.post(`/api/ops/venues/${no}/archive`, {}),
  unarchiveVenue: (no) => client.post(`/api/ops/venues/${no}/unarchive`, {}),

  // —— 合同审批 ——
  getContract: (no) => client.get(`/api/ops/contracts/${no}`),
  contractSummary: () => client.get("/api/ops/contracts/summary"),
  listContractLogs: (no) => client.get(`/api/ops/contracts/${no}/logs`),
  submitContract: (no) => client.post(`/api/ops/contracts/${no}/submit`, {}),
  withdrawContract: (no, note) => client.post(`/api/ops/contracts/${no}/withdraw`, { note }),
  auditContract: (no, result, reason) => client.post(`/api/ops/contracts/${no}/audit`, { result, reason }),
  cosignContract: (no, result, reason) => client.post(`/api/ops/contracts/${no}/cosign`, { result, reason }),
  signContract: (no, signedAt, fileNos) => client.post(`/api/ops/contracts/${no}/sign`, { signedAt, fileNos }),
  terminateContract: (no, reason, effectiveAt) => client.post(`/api/ops/contracts/${no}/terminate`, { reason, effectiveAt }),
  auditContractTermination: (no, result, reason) =>
    client.post(`/api/ops/contracts/${no}/termination/audit`, { result, reason }),
  renewContract: (no) => client.post(`/api/ops/contracts/${no}/renew`, {}),
  supplementContract: (no, startAt) => client.post(`/api/ops/contracts/${no}/supplement`, { startAt }),

  // —— 站点状态机与门禁 ——
  getSite: (no) => client.get(`/api/ops/sites/${no}`),
  siteSummary: () => client.get("/api/ops/sites/summary"),
  listSiteStatusLogs: (no) => client.get(`/api/ops/sites/${no}/status-logs`),
  siteOpeningChecklist: (no) => client.get(`/api/ops/sites/${no}/opening-checklist`),
  siteCloseGate: (no) => client.get(`/api/ops/sites/${no}/close-gate`),
  withdrawSite: (no, reason, plannedAt) => client.post(`/api/ops/sites/${no}/withdraw`, { reason, plannedAt }),
  closeSite: (no, note) => client.post(`/api/ops/sites/${no}/close`, { note }),
  listSiteSurveys: (no) => client.get(`/api/ops/sites/${no}/surveys`),
  recordSiteSurvey: (no, req) => client.post(`/api/ops/sites/${no}/surveys`, req),
};
