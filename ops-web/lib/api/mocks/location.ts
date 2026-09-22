// 覆盖范围：场所域 —— 站点 / 点位 / 场地方 / 合同 / 商机线索 / 选址分析 /
// 门店 Onboarding / 站点生命周期。
import * as db from "../../mock/db";
// 阶段流转走子模块直取（同 mocks/workorder.ts 的 `wo`）：校验与留痕都在 db 层，本文件只延迟透传。
import * as loc from "../../mock/db/location";
import type { LocationApi } from "../contracts/location";
import type { PageQ, ArchiveQ , ReportQ } from "../query";
import type { Site, SitePoint } from "../../types";
import { wait } from "./_wait";

export const locationMock: LocationApi = {
  listSites: (q: ArchiveQ = {}) =>
    wait(db.paginate(db.sites, q.page, q.size, (s) => db.liveHit(s, q.showArchived) && db.kwHit(q.keyword, s.name, s.venueName, s.regionId))),
  // async：让 assertSiteCoords 抛的 SiteCoordError 变成 rejected promise，交给全局 MutationCache 弹错
  saveSite: async (s) => {
    const idx = db.sites.findIndex((x) => x.siteNo === s.siteNo);
    const merged = { ...(db.sites[idx] ?? { name: "", venueName: "", agentNo: null, regionId: "", address: "", sceneType: "商场", pointCount: 0, cabinetCount: 0, status: "ACTIVE", archivedAt: null, siteNo: `ST${300 + db.sites.length}` }), ...s } as Site;
    // 坐标在**落库前**校验：写进去再发现飘到印度洋，地图上只会表现为「站点不见了」
    loc.assertSiteCoords(merged.lat, merged.lng);
    merged.lat = Number(merged.lat); merged.lng = Number(merged.lng); // 表单 number input 可能给字符串
    if (idx >= 0) db.sites[idx] = merged; else db.sites.unshift(merged);
    return wait(merged, 350);
  },
  listLocations: (q: ArchiveQ = {}) =>
    wait(db.paginate(db.locations, q.page, q.size, (l) => db.liveHit(l, q.showArchived) && db.kwHit(q.keyword, l.name, l.siteName))),
  savePoint: (l) => {
    const idx = db.locations.findIndex((x) => x.locationNo === l.locationNo);
    const merged = { ...(db.locations[idx] ?? { name: "", siteNo: "", siteName: "", spotDesc: "", cabinetCount: 0, status: "ACTIVE", archivedAt: null, locationNo: `LOC${200 + db.locations.length}` }), ...l } as SitePoint;
    if (idx >= 0) db.locations[idx] = merged; else db.locations.unshift(merged);
    return wait(merged, 350);
  },
  listVenues: (q: ArchiveQ = {}) =>
    wait(db.paginate(db.venues, q.page, q.size, (v) => db.liveHit(v, q.showArchived) && db.kwHit(q.keyword, v.name))),
  listContracts: (q: PageQ = {}) => wait(db.paginate(db.contracts, q.page, q.size, (c) => db.kwHit(q.keyword, c.venueName, c.siteName))),

  // 场所扩展
  listLeads: (q: PageQ = {}) => wait(db.listLeads(q)),
  listSiteAnalysis: (q: ReportQ = {}) => wait(db.listSiteAnalysis(q)),
  saveLead: (x) => wait(db.saveLead(x), 350),
  saveVenue: (x) => wait(db.saveVenue(x), 350),
  saveContract: (x) => wait(db.saveContract(x), 350),

  // 跟进记录 / 合同附件：校验与留痕都在 db 层，本文件只延迟透传（async 让守卫抛错变 rejected）
  listLeadFollowUps: (leadNo, q: PageQ = {}) => wait(loc.listLeadFollowUps(leadNo, q)),
  addLeadFollowUp: async (leadNo, req) => wait(loc.addLeadFollowUp(leadNo, req), 350),
  addContractAttachment: async (contractNo, req) => wait(loc.addContractAttachment(contractNo, req), 450),
  removeContractAttachment: async (contractNo, attachNo) => wait(loc.removeContractAttachment(contractNo, attachNo), 350),

  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q: PageQ = {}) => wait(db.listVenueOnboardings(q)),
  saveVenueOnboarding: (x) => wait(db.saveVenueOnboarding(x), 350),
  listSiteLifecycles: (q: PageQ = {}) => wait(db.listSiteLifecycles(q)),
  // async：让 db 抛的 SiteLifecycleError 变成 rejected promise，交给全局 MutationCache 弹错，不默默吞掉非法流转
  changeSiteStage: async (siteNo, req) => wait(loc.changeSiteStage(siteNo, req), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveSite: async (no) => wait(db.archiveSite(no), 350),
  unarchiveSite: async (no) => wait(db.unarchiveSite(no), 350),
  archivePoint: async (no) => wait(db.archivePoint(no), 350),
  unarchivePoint: async (no) => wait(db.unarchivePoint(no), 350),
  archiveVenue: async (no) => wait(db.archiveVenue(no), 350),
  unarchiveVenue: async (no) => wait(db.unarchiveVenue(no), 350),
};
