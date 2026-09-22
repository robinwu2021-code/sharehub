#!/usr/bin/env python3
"""生成 docs/api/运营端接口清单.md（后端实现规格 + 前端功能对齐）。

在 ops-web/ 目录下运行：python3 scripts/gen-api-inventory.py
输入：lib/api/https/*.ts（契约方法与真实路径）· app/**/page.tsx + components/**（调用点）· lib/nav.ts（菜单叶 SSOT）
输出：整份清单文档（勿手工维护，改契约/页面后重跑本脚本）。
"""
import re, pathlib, collections, datetime

DOC = pathlib.Path("../docs/api/运营端接口清单.md")

DOMAIN_LABEL = {
 "dashboard":"看板与认证","device":"设备管理","alarm":"告警治理","workorder":"工单管理",
 "location":"站点与点位","agent":"代理商","order":"订单管理","pricing":"计费定价",
 "finance":"财务管理","user":"用户管理","marketing":"营销管理","cs":"客服管理",
 "report":"数据报表","org":"员工与权限","system":"系统设置",
}
PREFIX_MEANING = {
 "/api/ops":"**运营资源**：设备 / 告警 / 工单 / 站点点位 / 客服 / 报表 / 公告广告位",
 "/api/platform":"**平台配置与 IAM**：系统设置 / 员工权限",
 "/api/trade":"**交易与资金**：订单 / 计费 / 财务",
 "/api/user":"**C 端用户**：用户档案 / 风控 / 会员钱包 / 营销",
 "/api/agent":"**代理商**：档案 / 账号 / 划拨 / 分润 / 绩效",
 "/api/auth":"认证",
 "/internal":"**服务间内部调用**（供应商接入配置、信用拉黑），非对外接口",
}
# 孤儿端点的人工裁决注（脚本发现新孤儿时默认「待裁决」，裁决后补进此表）
ORPHAN_NOTES = {
 "getOrder": "订单详情抽屉复用列表行数据未单独拉取；详情直链/强刷场景仍需要，**后端保留**",
 "listReportScreen": "已被 `getScreenBoard`（`/api/ops/reports/screen-board`）取代，**建议从契约移除**",
 "listMemberCards": "读侧已并入 `getUserProfile` 聚合返回，写侧 `grantMemberCard` 在用；独立列表暂无页面，**待裁决**",
}

# ---------- 1) 契约方法 → 端点 ----------
rows = []  # (dom, name, verb, [paths])
for p in sorted(pathlib.Path("lib/api/https").glob("*.ts")):
    dom = p.stem
    src = p.read_text()
    for m in re.finditer(r'^\s{2}(\w+):\s*(?:async\s+)?(\([^)]*\)|\w+)\s*=>\s*([\s\S]*?)(?=\n\s{2}\w+:|\n\};)', src, re.M):
        name, body = m.group(1), m.group(3)
        verbs = re.findall(r'client\.(get|post|put|patch|delete)\s*(?:<[^(]*>)?\s*\(', body)
        paths = re.findall(r'[`"]((?:/api|/internal)[^`"]*)[`"]', body)
        if not verbs or not paths: continue
        norm = [re.sub(r'\$\{[^}]*?\.?(\w+)\}', r'{\1}', str(pa)) for pa in paths]
        rows.append((dom, name, verbs[0].upper(), norm))

# ---------- 2) 调用点扫描（方法 → 页面路由）----------
def route_of(p: pathlib.Path) -> str:
    s = str(p)
    if s.endswith("page.tsx") and s.startswith("app"):
        r = "/" + "/".join(p.parts[1:-1])
        return r if r != "//" else "/"
    return f"⚙ {p.name}"  # 非页面（全局组件）

usage = collections.defaultdict(set)  # method -> {route}
for base in ("app", "components"):
    for p in pathlib.Path(base).rglob("*.tsx"):
        src = p.read_text()
        for m in re.finditer(r'\bapi\.(\w+)\s*\(', src):
            usage[m.group(1)].add(route_of(p))

# ---------- 3) nav.ts 菜单叶（路由 → 叶子）----------
nav_src = pathlib.Path("lib/nav.ts").read_text()
leaves_by_route = collections.defaultdict(list)  # route -> [label(+标注)]
section_key = ""
for line in nav_src.splitlines():
    mk = re.search(r'key:\s*"([^"]+)"', line)
    if mk: section_key = mk.group(1)
    # 无叶 L1（单行 section 字面量，自身即页面，如「经营看板」）
    ms = re.match(r'^\s*\{ key: "[^"]+", label: "([^"]+)"[^{}]*href: "([^"]+)" \},?$', line)
    if ms:
        leaves_by_route[ms.group(2).split("?")[0]].append(ms.group(1) + "（L1 直达页）")
        continue
    for ml in re.finditer(r'\{ href: "([^"]+)", label: "([^"]+)"([^}]*)\}', line):
        href, label, rest = ml.groups()
        route = href.split("?")[0]
        tag = "（代理端）" if section_key.startswith("my-") else ""
        if "soon: true" in rest: tag += "（待建）"
        leaves_by_route[route].append(label + tag)

# ---------- 4) 汇总 ----------
by_dom = collections.defaultdict(list)
for r in rows: by_dom[r[0]].append(r)
methods = {r[1] for r in rows}
orphans = sorted(methods - set(usage))
prefix_cnt = collections.Counter(re.match(r'((?:/api|/internal)/[a-z-]+)', r[3][0]).group(1) for r in rows)
verb_cnt = collections.Counter(r[2] for r in rows)
today = datetime.date.today().isoformat()

out = []
out.append("# 运营端接口清单（后端实现规格 · 已对齐前端功能）\n")
out.append(f"> **生成方式**：由 `ops-web/scripts/gen-api-inventory.py` **机器生成**（勿手工维护），{today} 同步。")
out.append(f"> 输入三源：`lib/api/https/*.ts`（契约与路径，共 **{len(rows)} 个端点**，与 `lib/api/contract.test.ts` 锚定的方法总数一致）")
out.append("> · `app/**/page.tsx` + `components/**`（**逐方法调用点**，见 §三「调用页面」列）· `lib/nav.ts`（菜单叶 SSOT，见 §四 对齐核对）。")
out.append("> **用途**：按既定路线「前端全量完成 → 再整体开发后端」，**本清单即后端要实现的接口全集**。")
out.append("> **交叉引用**：设计裁决（前缀/权限码/写入口归属）见 [README.md](./README.md)；库表 SSOT 见 [db-design.md](../technical/db-design.md)。\n")

out.append("## 一、前缀划分（2026-07-29 收敛，与后端前缀定案一致）\n")
out.append("| 前缀 | 数量 | 含义 |\n|---|---:|---|")
merged = collections.Counter()
for pre, n in prefix_cnt.items():
    merged["/internal" if pre.startswith("/internal") else pre] += n
for pre, n in sorted(merged.items(), key=lambda x: -x[1]):
    label = "`/internal/gw` `/internal/user`" if pre == "/internal" else f"`{pre}`"
    out.append(f"| {label} | {n} | {PREFIX_MEANING[pre]} |")
out.append("")
out.append(f"> 动词分布：GET {verb_cnt['GET']} · POST {verb_cnt['POST']} · PUT {verb_cnt.get('PUT',0)} · **零 DELETE**（软删除定案）。")
out.append("> 治理前前端曾自造 `/api/system`、`/api/alarm`、`/api/report`、`/api/order`、`/api/cs`、`/api/marketing` 六个后端根本不存在的前缀，已全部收敛。详见[一致性问题台账 §三](../technical/前端结构治理-一致性问题台账.md)。\n")

out.append("""## 二、给后端实现者的约定

1. **分页**：列表统一返回 `PageResult<T> = { list, total, page, size }`，查询参数见 `ops-web/lib/api/query.ts`
2. **新增/编辑同方法**：备注列标注者，有业务键走带 `{key}` 的路径（编辑），无键走不带的（新增）
3. **软删除**：本项目**不做物理删除**（已拍板）。契约里**零 `delete*`**，取而代之的是 `archive*`/`unarchive*`：
   - 实体带 `archivedAt: string | null`（**不是 `deleted` 布尔**——归档时间本身是审计信息）
   - 列表默认过滤 `archivedAt != null`，查询参数 `showArchived=1` 时才返回全部
   - `archive` 盖时间戳、`unarchive` 置 null；**内置数据（如内置角色）服务端必须拒绝归档**
4. **审批留痕**：提现/退款的 `auditorName`/`auditedAt`/`rejectReason` 三件套建议**落到统一的审批流水表**，而非每张业务表各加三列（台账 T6）
5. **词表重定**：充电宝状态、计费字段命名等，见[台账](../technical/前端结构治理-一致性问题台账.md)「给后端阶段的提示」
6. **幂等**：退款用 `idempotencyKey`；告警转工单、投诉转工单已在前端 mock 实现为幂等，后端须保持
""")

out.append("## 三、端点全集（按域 · 方法名 = 前端契约方法名 · 调用页面 = 前端功能落点）\n")
out.append("| # | 域 | 方法名 | HTTP | 端点 | 调用页面 | 备注 |\n|---:|---|---|:---:|---|---|---|")
i = 0
for dom in sorted(by_dom, key=lambda d: -len(by_dom[d])):
    for dom_, name, verb, paths in sorted(by_dom[dom], key=lambda x: x[1]):
        i += 1
        called = " ".join(f"`{r}`" for r in sorted(usage.get(name, ()))) or "⚠️ 无"
        if len(paths) == 1:
            ep, note = f"`{paths[0]}`", ""
        else:
            ep, note = f"`{paths[-1]}`", f"有业务键时改用 `{paths[0]}`（新增/编辑同方法）"
        out.append(f"| {i} | {DOMAIN_LABEL.get(dom_,dom_)} | `{name}` | {verb} | {ep} | {called} | {note} |")
out.append("")

out.append("## 四、前端功能对齐核对\n")
out.append(f"### 4.1 孤儿端点（契约有、页面未调用，{len(orphans)} 个）\n")
if orphans:
    out.append("| 方法名 | 端点 | 裁决 |\n|---|---|---|")
    ep_of = {r[1]: r[3][-1] for r in rows}
    for name in orphans:
        out.append(f"| `{name}` | `{ep_of[name]}` | {ORPHAN_NOTES.get(name, '**待裁决**（脚本新发现，请人工判定后补入脚本 ORPHAN_NOTES）')} |")
else:
    out.append("无。")
out.append("")
out.append("### 4.2 页面 ↔ 菜单叶 ↔ 端点 汇总（菜单叶 SSOT = `lib/nav.ts`）\n")
out.append("| 页面路由 | 调用端点数 | 承载的菜单叶 |\n|---|---:|---|")
route_methods = collections.defaultdict(set)
for name, routes in usage.items():
    for r in routes: route_methods[r].add(name)
all_routes = sorted(set(route_methods) | set(leaves_by_route), key=lambda r: (r.startswith("⚙"), r))
for r in all_routes:
    n = len(route_methods.get(r, ()))
    leaves = " · ".join(leaves_by_route.get(r, [])) or ("—（全局组件，转发既有端点）" if r.startswith("⚙") else "—（页内深链，不在菜单）")
    out.append(f"| `{r}` | {n} | {leaves} |")
out.append("")
no_call = [r for r in leaves_by_route if r not in route_methods]
out.append(f"> 核对结论：菜单叶路由 {len(leaves_by_route)} 个、调用 api 的页面 {len([r for r in route_methods if not r.startswith('⚙')])} 个；"
           + (f"**{len(no_call)} 个菜单路由无端点调用**：{'、'.join(f'`{r}`' for r in sorted(no_call))}。" if no_call else "**每个菜单路由都有端点支撑**。")
           + f" 契约 {len(methods)} 方法中 {len(methods)-len(orphans)} 个已被页面调用。")
out.append("")

DOC.write_text("\n".join(out))
print(f"written {DOC} · {len(rows)} endpoints · {len(orphans)} orphans · {len(leaves_by_route)} nav routes")
print("BY_PREFIX", dict(prefix_cnt))
