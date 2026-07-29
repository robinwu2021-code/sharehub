// 覆盖范围：场所域 —— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。端点前缀：/api/ops/**
import { client } from "../http-client";
import type { LocationApi } from "../contracts/location";
import type { PageQ } from "../query";

export const locationHttp: LocationApi = {
  listSites: (q?: PageQ) => client.get("/api/ops/sites", q),
  saveSite: (s) => client.post(s.siteNo ? `/api/ops/sites/${s.siteNo}` : "/api/ops/sites", s),
  listLocations: (q?: PageQ) => client.get("/api/ops/locations", q),
  savePoint: (l) => client.post(l.locationNo ? `/api/ops/locations/${l.locationNo}` : "/api/ops/locations", l),
  listVenues: (q?: PageQ) => client.get("/api/ops/venues", q),
  listContracts: (q?: PageQ) => client.get("/api/ops/contracts", q),

  // 场所扩展
  listLeads: (q?: PageQ) => client.get("/api/ops/leads", q),
  listSiteAnalysis: (q?: PageQ) => client.get("/api/ops/site-analysis", q),
  saveLead: (x) => client.post(x.leadNo ? `/api/ops/leads/${x.leadNo}` : "/api/ops/leads", x),
  saveVenue: (x) => client.post(x.venueNo ? `/api/ops/venues/${x.venueNo}` : "/api/ops/venues", x),
  saveContract: (x) => client.post(x.contractNo ? `/api/ops/contracts/${x.contractNo}` : "/api/ops/contracts", x),

  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q?: PageQ) => client.get("/api/ops/venue-onboardings", q),
  saveVenueOnboarding: (x) => client.post(x.onboardingNo ? `/api/ops/venue-onboardings/${x.onboardingNo}` : "/api/ops/venue-onboardings", x),
  listSiteLifecycles: (q?: PageQ) => client.get("/api/ops/site-lifecycles", q),
};
