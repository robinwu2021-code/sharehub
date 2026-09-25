// 清退门禁「去处理」链接的改写：后端 AgentExitServiceImpl 给的 fixHref 是它自己想象的路由
// （`/agents/AG001?tab=assign`、`/finance/shares?payeeNo=…`），运营端并没有这些路径 ——
// 原样渲染的话，门禁最需要的那个「去处」点进去是 404。
//
// 改写只在这一处：后端改成给真实路由后，这里的规则自然不再命中（原样透传），不必同步删。
// 真正的修法是后端按运营端路由出链接（已在回报里列为后端待办）。

const RULES: [RegExp, (m: RegExpMatchArray, q: URLSearchParams) => string][] = [
  // 划拨 / 回收在代理页的「设备/点位划拨」页签
  [/^\/agents\/[^/?]+$/, (_m, q) => (q.get("tab") === "sites" ? "/operation/sites" : "/agents?tab=assign")],
  [/^\/work-orders$/, (_m, q) => `/work-orders?view=list${q.get("assignee") ? `&assignee=${encodeURIComponent(q.get("assignee")!)}` : ""}`],
  [/^\/finance\/(shares|settlements|withdrawals)$/, (m, q) => {
    const tab = m[1] === "shares" ? "records" : m[1];
    const payee = q.get("payeeNo");
    return `/finance?tab=${tab}${payee ? `&payee=${encodeURIComponent(payee)}` : ""}`;
  }],
];

/** 后端 fixHref → 运营端真实路由；不认识的原样返回。 */
export function exitFixHref(href: string | null | undefined): string | null {
  if (!href) return null;
  const [path, query = ""] = href.split("?");
  const q = new URLSearchParams(query);
  for (const [re, to] of RULES) {
    const m = path.match(re);
    if (m) return to(m, q);
  }
  return href;
}
