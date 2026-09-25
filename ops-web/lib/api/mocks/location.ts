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
  listSiteAgents: (siteNo) => wait(db.listSiteAgents(siteNo)),
  saveSiteAgent: async (siteNo, x) => wait(db.saveSiteAgent(siteNo, x), 350),
  removeSiteAgent: async (siteNo, id) => wait(db.removeSiteAgent(siteNo, id), 350),
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
  /**
   * 商机保存 + 拓展归因（ADR-027 §五）——与后端 `LeadServiceImpl.save` 同一条规则。
   *
   * 签下且归属是伙伴时，顺带把「这个场地是谁谈下来的」落成一行「拓展」责任，
   * 那是拓展佣金的依据。mock 不能只存不写，否则页面上看不出这件事发生过。
   *
   * **条件是「已签 + 已指定站点」而不是「阶段刚翻成 SIGNED」**：先签后建站是常态，
   * 签的那一刻没有站点可挂；站点补填上去的那一次保存会补写。幂等由 saveSiteAgent 的唯一性保证。
   *
   * 组合写在这一层而不是 db 层：责任行住在 agent 模块，从 db/location 里 import 它
   * 会闭合 device → location → agent → device 的环（实测整批 mock 测试加载失败）。
   */
  saveLead: (x) => {
    const saved = db.saveLead(x);
    if (saved.stage === "SIGNED" && saved.ownerType === "AGENT" && saved.siteNo && saved.owner) {
      try {
        db.saveSiteAgent(saved.siteNo, {
          agentNo: saved.owner, role: "DEVELOP",
          remark: `来自商机 ${saved.leadNo}：${saved.venueName}`,
        });
      } catch {
        // 最常见的是「该伙伴在本站点已有牵线，与拓展互斥」——真实的业务冲突，
        // 要让运营看见并自己决定算哪一个，但**不能把商机也存不上**
      }
    }
    return wait(saved, 350);
  },
  saveVenue: (x) => wait(db.saveVenue(x), 350),
  /**
   * 合同保存 + 一次性牵线费（ADR-027 §四）——与后端 `LocService.saveContract` 同一条规则。
   *
   * 状态为 ACTIVE 即视为已签：本域的状态词表只有 ACTIVE / EXPIRED，没有草稿态 ——
   * 运营录入的本来就是一份已经签好的纸质合同。
   *
   * **每次保存都尝试结算，靠「同一合同同一伙伴只结一次」去重**，
   * 而不是判「状态首次变 ACTIVE」：漏判的后果是牵线人一分钱都收不到，且没人看得出来。
   */
  saveContract: (x) => {
    const saved = db.saveContract(x);
    if (saved.status === "ACTIVE" && saved.siteNo) {
      for (const r of db.listSiteAgents(saved.siteNo)) {
        // 没配金额 ≠ 0。记一条 0 元明细会把「该有人去配」和「明确不付」混为一谈。
        if (r.role !== "REFER" || r.oneOffAmount == null || r.oneOffAmount <= 0) continue;
        const already = db.shareRecords.some(
          (rec) => rec.orderNo === saved.contractNo && rec.payeeNo === r.agentNo && rec.basis === "REFER");
        if (already) continue;
        db.shareRecords.push({
          recordNo: `SREC${9000 + db.shareRecords.length}`,
          // 推荐返佣是平台事后结给代理的，不经支付渠道分账 → 台账模式
          mode: "LEDGER",
          orderNo: saved.contractNo,          // 来源单据就是合同，不是某一张订单
          dimension: "AGENT", payeeNo: r.agentNo, payeeName: r.agentName ?? r.agentNo,
          basis: "REFER",
          // 一次性对价没有「基数」，基数就是它自己（与后端 ReferFeeGeneratorImpl 同口径）
          grossAmount: r.oneOffAmount, status: "PENDING", settleNo: null,
          amount: r.oneOffAmount, rate: 0,    // 与比例无关：一次性对价不随 GMV 走
          currency: "AED",
          period: new Date().toISOString().slice(0, 7),
          createdAt: new Date().toISOString(),
        });
      }
    }
    return wait(saved, 350);
  },

  // 跟进记录 / 合同附件：校验与留痕都在 db 层，本文件只延迟透传（async 让守卫抛错变 rejected）
  listLeadFollowUps: (leadNo, q: PageQ = {}) => wait(loc.listLeadFollowUps(leadNo, q)),
  addLeadFollowUp: async (leadNo, req) => wait(loc.addLeadFollowUp(leadNo, req), 350),
  addContractAttachment: async (contractNo, req) => wait(loc.addContractAttachment(contractNo, req), 450),
  removeContractAttachment: async (contractNo, attachNo) => wait(loc.removeContractAttachment(contractNo, attachNo), 350),

  // 门店 Onboarding / 生命周期
  listVenueOnboardings: (q: PageQ = {}) => wait(db.listVenueOnboardings(q)),
  // async：校验抛的 ApiError 要变成 rejected promise，否则全局 MutationCache 接不到
  reviewVenueOnboarding: async (no, approve, note) =>
    wait(db.reviewVenueOnboarding(no, approve, note), 350),
  // async：save 现在会校验「已审核不可再改」，同步抛出的 ApiError 到不了全局 MutationCache
  saveVenueOnboarding: async (x) => wait(db.saveVenueOnboarding(x), 350),
  listSiteLifecycles: (q: PageQ & { phase?: string } = {}) => wait(db.listSiteLifecycles(q)),
  siteLifecycleFunnel: () => wait(db.siteLifecycleFunnel()),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveSite: async (no) => wait(db.archiveSite(no), 350),
  unarchiveSite: async (no) => wait(db.unarchiveSite(no), 350),
  archivePoint: async (no) => wait(db.archivePoint(no), 350),
  unarchivePoint: async (no) => wait(db.unarchivePoint(no), 350),
  archiveVenue: async (no) => wait(db.archiveVenue(no), 350),
  unarchiveVenue: async (no) => wait(db.unarchiveVenue(no), 350),
};
