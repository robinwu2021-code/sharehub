// 从 lib/nav.ts + lib/permissions.ts 真值生成「运营端功能矩阵」markdown。
// 一次性脚本：node scripts/gen-ops-matrix.mjs > ../docs/requirements/运营端功能矩阵.md
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const navSrc = fs.readFileSync(path.join(__dirname, "../lib/nav.ts"), "utf8");

// ── 角色→权限码（与 lib/permissions.ts 保持同步）──
const ROLE_PERMS = {
  ADMIN: ["*"],
  OPS: [
    "dashboard:overview:read","dashboard:todo:read",
    "device:cabinet:*","device:slot:read","device:powerbank:*",
    "device:command:*","device:inventory:*","device:ota:read","device:vendor:read",
    "device:card:read","device:rfid:read",
    "location:poi:read","location:venue:read",
    "order:order:read","order:exception:read","order:exception:handle","order:free:read",
    "workorder:*","agent:scope:assign",
    "report:device:read","report:location:read",
    "operations:app_version:read","operations:battery:read","operations:feedback:read",
    "alert:record:read","alert:notify:read","alert:code:read","alert:rule:read","alert:fault:read",
    "msg:record:read","msg:template:read","system:dict:read",
  ],
  CS: [
    "dashboard:overview:read","dashboard:todo:read",
    "device:cabinet:read","device:slot:read","device:command:send",
    "order:order:read","order:order:export","order:exception:read","order:exception:handle",
    "order:intervene:execute","order:refund:apply","order:free:read",
    "user:cuser:read","user:risk:read","user:risk:update","user:member:read","user:wallet:read",
    "user:kyc:read","user:credit:read",
    "workorder:wo:read","workorder:wo:create",
    "cs:*","alert:record:read",
    "topup:package:read","topup:order:read",
    "marketing:coupon:read","marketing:coupon:issue","marketing:push:send",
    "msg:record:read","msg:blocked:read","msg:template:read",
  ],
  FINANCE: [
    "dashboard:overview:read","dashboard:todo:read",
    "order:order:read","order:order:export","order:refund:audit",
    "pricing:*","finance:*",
    "agent:agent:read","agent:share:config","agent:settlement:read","agent:performance:read",
    "location:venue:read","location:contract:read","location:analysis:read",
    "user:cuser:read","user:member:read","user:wallet:read",
    "report:*","org:audit:read",
    "operations:bank_account:read","operations:fx:read",
    "topup:package:read","topup:order:read",
  ],
  BD: [
    "dashboard:overview:read",
    "location:*","pricing:rule:read","pricing:rule:update",
    "marketing:*","agent:*",
    "finance:share_rule:read","finance:share_record:read","finance:revshare:read",
    "report:*",
    "operations:announcement:read","operations:feedback:read",
  ],
  VIEWER: [
    "dashboard:overview:read","dashboard:todo:read",
    "device:cabinet:read","device:slot:read","order:order:read",
    "location:poi:read","location:venue:read","location:analysis:read",
    "finance:share_rule:read","finance:share_record:read","finance:settlement:read","finance:withdrawal:read",
    "report:*",
  ],
  AGENT: [
    "dashboard:overview:read",
    "device:cabinet:read","device:slot:read",
    "workorder:wo:create","order:order:read",
    "finance:share_record:read","finance:withdrawal:apply",
    "agent:settlement:read","location:poi:read",
  ],
};
const matchP = (pattern, code) => pattern === code || (pattern.endsWith("*") && code.startsWith(pattern.slice(0, -1)));
const can = (role, code) => (ROLE_PERMS[role] ?? []).some((p) => matchP(p, code));
const canModule = (role, mod) => (ROLE_PERMS[role] ?? []).some((p) => p === "*" || p === mod || p.startsWith(mod + ":"));
// 叶子对角色可见（忽略分期锁；= 稳态可访问性）：canModule && (perm ? can : true)
const leafVisible = (role, mod, perm) => canModule(role, mod) && (perm ? can(role, perm) : true);

// ── 解析 nav.ts ──
const DOMAIN_LABEL = {
  overview:"概览", operations:"运营管理", devices:"设备管理", alerts:"告警中心",
  orders:"订单管理", topup:"充值管理", members:"会员管理", partners:"合作伙伴",
  finance:"财务管理", marketing:"营销中心", integrations:"集成中心",
  messaging:"消息中心", analytics:"数据报表", access:"系统权限",
};
const lines = navSrc.split(/\r?\n/);
let curDomain = null, curModule = null;
const rows = []; // {domain, module, moduleKey, modPrefix, label, href, perm, phase}
for (const raw of lines) {
  const domM = raw.match(/^    key:\s*"([a-z_]+)",\s*$/);
  if (domM && !raw.includes("module:")) { curDomain = domM[1]; continue; }
  const trimmed = raw.trim();
  const isModule = raw.includes("key:") && raw.includes("module:") && raw.includes("label:") && !trimmed.startsWith("{ href");
  if (isModule) {
    curModule = {
      key: raw.match(/key:\s*"([^"]+)"/)?.[1],
      label: raw.match(/label:\s*"([^"]+)"/)?.[1],
      mod: raw.match(/module:\s*"([^"]+)"/)?.[1],
    };
    continue;
  }
  if (trimmed.startsWith("{ href:") && trimmed.includes("label:")) {
    rows.push({
      domain: curDomain,
      module: curModule?.label,
      modPrefix: curModule?.mod,
      label: raw.match(/label:\s*"([^"]+)"/)?.[1],
      href: raw.match(/href:\s*"([^"]+)"/)?.[1],
      perm: raw.match(/perm:\s*"([^"]+)"/)?.[1] ?? null,
      phase: raw.match(/phase:\s*(\d)/)?.[1] ? Number(raw.match(/phase:\s*(\d)/)[1]) : 1,
    });
  }
}

const COLS = ["OPS","CS","FINANCE","BD","VIEWER","AGENT"];
const mark = (b) => (b ? "●" : "·");
// 数据范围维度（按模块前缀，设计口径；实现现状仅 loc_site 已注册）
const SCOPE = {
  dashboard:"按角色范围聚合", location:"REGION/SITE/AGENT", pricing:"ALL·平台配置",
  operations:"ALL·平台配置", device:"AGENT/REGION（挂站点）", alert:"AGENT/REGION（挂设备）",
  workorder:"AGENT/REGION（挂设备）", order:"AGENT（挂站点）;CS/FIN全域", topup:"ALL·平台",
  user:"ALL·租户级", agent:"AGENT·本人", finance:"AGENT·分润/ALL·平台账本",
  marketing:"ALL·平台", integration:"ALL·平台", msg:"ALL·平台",
  report:"REGION/AGENT 聚合", org:"ALL·平台配置", system:"ALL·平台配置",
};
const roleCell = (r) => {
  const s = COLS.filter((c) => leafVisible(c, r.modPrefix, r.perm));
  return s.length === 6 ? "全6角色" : (s.length ? s.join("/") : "仅ADMIN");
};

// ── 汇总 ──
const domainOrder = [...new Set(rows.map((r) => r.domain))];
const phaseTally = {}; // domain -> [p1,p2,p3]
for (const d of domainOrder) phaseTally[d] = [0,0,0];
for (const r of rows) phaseTally[r.domain][r.phase - 1]++;
const total = [0,0,0];
for (const d of domainOrder) for (let i=0;i<3;i++) total[i]+=phaseTally[d][i];

// 域×角色（域内任一叶对该角色可见）
const domRole = {};
for (const d of domainOrder) { domRole[d] = {}; for (const c of COLS) domRole[d][c] = false; domRole[d].ADMIN=true; }
for (const r of rows) for (const c of COLS) if (leafVisible(c, r.modPrefix, r.perm)) domRole[r.domain][c] = true;

// 每角色可访问叶数
const roleLeafCount = { ADMIN: rows.length };
for (const c of COLS) roleLeafCount[c] = rows.filter((r)=>leafVisible(c, r.modPrefix, r.perm)).length;

// ── 输出 ──
const out = [];
out.push("# 运营端功能矩阵（V2 四级体系）");
out.push("");
out.push("> 状态：分析记录 · 生成 2026-07-14 · **自动生成于 `ops-web/lib/nav.ts` + `lib/permissions.ts` 真值**（勿手改，改源后重跑 `scripts/gen-ops-matrix.mjs`）");
out.push("> 口径：域(L1) → 模块(L2) → 子功能(L3)，共 **14 域 / 27 模块 / "+rows.length+" 叶**。");
out.push("> 阶段以 `PRD-分期路线图.md`（← `ref/充電寶專案_V4(1).pdf` 甲特图）为 SSOT；功能定义以 `运营端功能清单-V2四级体系.md` 为准。");
out.push("> **角色可见性 = 稳态权限视图**（`canModule && (perm?can:true)`），**不含分期锁**——分期锁是随 `CURRENT_PHASE` 解锁的时间闸，与权限正交。`ADMIN` 拥有 `*`，可见全部，故下表列省略。`●`=可见 `·`=不可见。");
out.push("");
out.push("---");
out.push("");

// 总览
out.push("## 一、总览");
out.push("");
out.push("**阶段分布：P1 "+total[0]+" · P2 "+total[1]+" · P3 "+total[2]+" = "+rows.length+" 叶**");
out.push("");
out.push("| # | 域 | P1 | P2 | P3 | 小计 |");
out.push("|---|---|:--:|:--:|:--:|:--:|");
domainOrder.forEach((d,i) => {
  const t = phaseTally[d];
  out.push(`| ${i+1} | ${DOMAIN_LABEL[d]} \`${d}\` | ${t[0]} | ${t[1]} | ${t[2]} | ${t[0]+t[1]+t[2]} |`);
});
out.push(`| | **合计** | **${total[0]}** | **${total[1]}** | **${total[2]}** | **${rows.length}** |`);
out.push("");
out.push("> 第 14 域 `overview` 概览（经营看板 `/`）为单模块 0 叶——看板/待办为页内 tab，不计入 "+rows.length+" 叶，故上表 13 行。全体角色（凭 `dashboard:overview:read`）均可见。");
out.push("");

// 域×角色
out.push("## 二、域 × 角色可见性");
out.push("");
out.push("> 「域内任一子功能对该角色可见」即记 `●`。细粒度到叶见第四节。");
out.push("");
out.push("| 域 | OPS | CS | FINANCE | BD | VIEWER | AGENT |");
out.push("|---|:--:|:--:|:--:|:--:|:--:|:--:|");
for (const d of domainOrder) {
  out.push(`| ${DOMAIN_LABEL[d]} | ${COLS.map((c)=>mark(domRole[d][c])).join(" | ")} |`);
}
out.push("");
out.push("**各角色可访问子功能数**（含分期锁定项，稳态口径）：");
out.push("");
out.push("| 角色 | ADMIN | OPS | CS | FINANCE | BD | VIEWER | AGENT |");
out.push("|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|");
out.push(`| 可见叶数 | ${roleLeafCount.ADMIN} | ${roleLeafCount.OPS} | ${roleLeafCount.CS} | ${roleLeafCount.FINANCE} | ${roleLeafCount.BD} | ${roleLeafCount.VIEWER} | ${roleLeafCount.AGENT} |`);
out.push("");

// 主矩阵
out.push("## 三、主功能矩阵（逐叶）");
out.push("");
out.push("> 每域一表。列：模块 / 子功能 / 路由 / 权限码 / 阶段 / 六角色可见性。`—perm` = 该叶无独立权限码，随父模块 `canModule`。");
out.push("");
for (const d of domainOrder) {
  out.push(`### ${DOMAIN_LABEL[d]} \`${d}\``);
  out.push("");
  out.push("| 模块 | 子功能 | 路由 | 权限码 | 阶段 | OPS | CS | FIN | BD | VIEW | AGT |");
  out.push("|---|---|---|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|");
  let lastMod = null;
  for (const r of rows.filter((x)=>x.domain===d)) {
    const modCell = r.module === lastMod ? "" : r.module;
    lastMod = r.module;
    const vis = COLS.map((c)=>mark(leafVisible(c, r.modPrefix, r.perm)));
    out.push(`| ${modCell} | ${r.label} | \`${r.href}\` | ${r.perm ? "`"+r.perm+"`" : "—perm"} | P${r.phase} | ${vis.join(" | ")} |`);
  }
  out.push("");
}

// 当期实际可点视图（CURRENT_PHASE=1）
const p1count = rows.filter((x)=>x.phase===1).length;
out.push("## 四、当期实际可点视图（CURRENT_PHASE=1）");
out.push("");
out.push("> 仅列 P1 叶（当期可点）+ 经营看板；其余 "+(rows.length-p1count)+" 叶（P2/P3）导航灰显、直达由 `PhaseGuard` 兜底。「数据范围」为设计口径（据 `权限管理方案.md`），非实现现状——后端目前仅注册 `loc_site`（AGENT/REGION/SITE）锚点，`ord_rent`/`wo_order` 有 `agent_no` 列但未注册（暂全局放行）。");
out.push("");
out.push("| 域 | 模块 | 子功能 | 路由 | 权限码 | 可见角色(除ADMIN) | 数据范围 |");
out.push("|---|---|---|---|---|---|---|");
out.push("| 概览 | 经营看板 | 经营看板 | `/` | `dashboard:overview:read` | 全6角色 | 按角色范围聚合 |");
{
  let lastMod = null;
  for (const r of rows.filter((x)=>x.phase===1)) {
    const dl = DOMAIN_LABEL[r.domain];
    out.push(`| ${dl} | ${r.module} | ${r.label} | \`${r.href}\` | ${r.perm ? "`"+r.perm+"`" : "—perm"} | ${roleCell(r)} | ${SCOPE[r.modPrefix] ?? "—"} |`);
  }
}
out.push("");
out.push(`**当期可点 = P1 ${p1count} 叶 + 经营看板 1 = ${p1count+1} 项。**`);
out.push("");

// 说明
out.push("## 五、读表说明");
out.push("");
out.push("- **阶段 phase**：`P1` 缺省即可点；`P2/P3` 在 `CURRENT_PHASE=1` 时导航灰显（徽章），页面直达由 `PhaseGuard` 兜底。进入下一阶段改 `NEXT_PUBLIC_CURRENT_PHASE` 重新构建即解锁，无需改码。");
out.push("- **权限 vs 分期正交**：本表可见性只按 RBAC 权限；分期锁独立。二者叠加后，用户实际可点 = 权限可见 且 未被分期锁。");
out.push("- **数据范围（data-scope）**：本表是「功能可见性」，不含行级数据范围。`AGENT` 见到的功能其数据在后端按 `agent_no` 收敛；`OPS/BD` 按区域、`VENUE` 按场地方等（见 `权限管理方案.md`）。");
out.push("- **A6 同路径多模块**：`finance` 6 模块共享 `/finance`、`orders` 3 模块共享 `/orders`、`marketing` 2 模块共享 `/marketing`，由 `findActiveModule` 依 `?tab=`/`?view=` 归属消歧。");
out.push("- **ADMIN**：拥有 `*`，可见全部 "+rows.length+" 叶，未单列。");
out.push("");
out.push(`> 源：\`ops-web/lib/nav.ts\`（${rows.length} 叶）· \`ops-web/lib/permissions.ts\`（7 角色）· 阶段 SSOT \`docs/requirements/PRD-分期路线图.md\`。`);
out.push("");

process.stdout.write(out.join("\n"));
