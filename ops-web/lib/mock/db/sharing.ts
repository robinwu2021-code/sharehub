// 分成的两个视角（清单 OM-S5 站点分成 / OM-S6 分成方分成）。
//
// ⚠️ **这是按现状聚合出来的读模型，不是新的存储**（清单 D2 未定案）：
// 分润规则表按「分成方」配比例、没有站点维度；进场合同里才有「场地方 × 站点」的比例。
// 所以站点视角只能这样拼：站点 --合同--> 场地方 --规则--> 比例，代理商比例走站点的 agentNo。
// D2 定案（建议以「规则 + 站点范围」为唯一来源）后，这里连同后端契约一起改。
import type { PayeeSharingRow, SiteSharingRow, SitePayee, PageQuery } from "../../types";
import { paginate, kwHit } from "./helpers";
import { sites, contracts } from "./location";
import { shareRules, shareRecords } from "./finance";
import { agents } from "./agent";

const DAY = 86400_000;

function payeesOf(siteName: string, venueName: string, agentNo: string | null): SitePayee[] {
  const out: SitePayee[] = [];
  // 场地方：优先用合同上的比例（合同是签过字的条款），没有合同再看规则
  const contract = contracts.find((c) => c.siteName === siteName && c.venueName === venueName);
  const venueRule = shareRules.find((r) => r.dimension === "VENUE" && r.payeeName === venueName);
  if (contract) {
    out.push({
      payeeType: "VENUE", payeeNo: venueName, payeeName: venueName, rate: contract.shareRate,
      mode: venueRule?.mode ?? "LEDGER", source: "CONTRACT", sourceNo: contract.contractNo,
    });
  } else if (venueRule) {
    out.push({
      payeeType: "VENUE", payeeNo: venueName, payeeName: venueName, rate: venueRule.rate,
      mode: venueRule.mode, source: "RULE", sourceNo: venueRule.ruleNo,
    });
  }
  // 代理商：站点归属哪个代理，就按那个代理的规则
  if (agentNo) {
    const agent = agents.find((a) => a.agentNo === agentNo);
    const rule = shareRules.find((r) => r.dimension === "AGENT" && r.payeeName === (agent?.name ?? agentNo));
    if (rule) {
      out.push({
        payeeType: "AGENT", payeeNo: agentNo, payeeName: agent?.name ?? agentNo, rate: rule.rate,
        mode: rule.mode, source: "RULE", sourceNo: rule.ruleNo,
      });
    }
  }
  return out;
}

function rowOf(siteNo: string): SiteSharingRow {
  const s = sites.find((x) => x.siteNo === siteNo)!;
  const payees = payeesOf(s.name, s.venueName, s.agentNo);
  const totalRate = Math.round(payees.reduce((n, p) => n + p.rate, 0) * 10000) / 10000;
  const contract = contracts.find((c) => c.siteName === s.name && c.venueName === s.venueName);
  const expired = contract ? new Date(contract.endAt).getTime() < Date.now() : false;

  let state: SiteSharingRow["state"] = "OK";
  let stateDetail = "";
  if (!payees.length) {
    state = "MISSING";
    stateDetail = "没有任何分成方，这个站点的收入全部留在平台";
  } else if (totalRate > 1) {
    state = "INVALID";
    stateDetail = `各方比例合计 ${(totalRate * 100).toFixed(1)}%，超过 100%`;
  } else if (expired && s.status === "ACTIVE") {
    state = "INVALID";
    stateDetail = `合同 ${contract!.contractNo} 已于 ${contract!.endAt.slice(0, 10)} 到期，站点仍在营业`;
  }

  return {
    siteNo: s.siteNo, siteName: s.name, venueName: s.venueName, payees,
    totalRate, platformRate: Math.round((1 - totalRate) * 10000) / 10000,
    contractEndAt: contract?.endAt ?? null, state, stateDetail,
  };
}

export function listSiteSharing(q: PageQuery & { state?: string; venueName?: string } = {}) {
  const rows = sites
    .filter((s) => !s.archivedAt)
    .map((s) => rowOf(s.siteNo))
    .filter((r) => (!q.state || r.state === q.state) && (!q.venueName || r.venueName === q.venueName))
    // 缺配置与异常排在最前——运营打开这一页就是来找这些的
    .sort((a, b) => order(a.state) - order(b.state) || a.siteName.localeCompare(b.siteName));
  return paginate(rows, q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName, x.venueName));
}
const order = (s: SiteSharingRow["state"]) => (s === "INVALID" ? 0 : s === "MISSING" ? 1 : 2);

/** 站点分成的统计条：已配置 / 缺配置 / 异常各多少。 */
export function getSiteSharingStats() {
  const all = sites.filter((s) => !s.archivedAt).map((s) => rowOf(s.siteNo));
  return {
    total: all.length,
    ok: all.filter((r) => r.state === "OK").length,
    missing: all.filter((r) => r.state === "MISSING").length,
    invalid: all.filter((r) => r.state === "INVALID").length,
  };
}

/** 按分成方聚合（同一份数据的另一个方向）。 */
export function listPayeeSharing(q: PageQuery & { payeeType?: string } = {}) {
  const since = Date.now() - 30 * DAY;
  const map = new Map<string, PayeeSharingRow>();
  for (const s of sites.filter((x) => !x.archivedAt)) {
    for (const p of payeesOf(s.name, s.venueName, s.agentNo)) {
      const key = `${p.payeeType}:${p.payeeName}`;
      const cur = map.get(key) ?? {
        payeeType: p.payeeType, payeeName: p.payeeName, siteCount: 0,
        minRate: 1, maxRate: 0, amount30d: 0, currency: "AED", sites: [],
      };
      cur.siteCount += 1;
      cur.minRate = Math.min(cur.minRate, p.rate);
      cur.maxRate = Math.max(cur.maxRate, p.rate);
      cur.sites.push({ siteNo: s.siteNo, siteName: s.name, rate: p.rate });
      map.set(key, cur);
    }
  }
  // 近 30 日分成金额取自分润明细（真实链路缺失时这里会是 0，页面如实说明）
  for (const row of map.values()) {
    row.amount30d = Math.round(
      shareRecords
        .filter((r) => r.payeeName === row.payeeName && new Date(r.createdAt).getTime() >= since)
        .reduce((n, r) => n + r.amount, 0) * 100,
    ) / 100;
    row.sites.sort((a, b) => b.rate - a.rate);
  }
  const rows = [...map.values()]
    .filter((r) => !q.payeeType || r.payeeType === q.payeeType)
    .sort((a, b) => b.siteCount - a.siteCount || a.payeeName.localeCompare(b.payeeName));
  return paginate(rows, q.page, q.size, (x) => kwHit(q.keyword, x.payeeName));
}
