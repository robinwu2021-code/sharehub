import re, pathlib, collections

DOMAIN_LABEL = {
 "dashboard":"看板与认证","device":"设备管理","alarm":"告警治理","workorder":"工单管理",
 "location":"站点与点位","agent":"代理商","order":"订单管理","pricing":"计费定价",
 "finance":"财务管理","user":"用户管理","marketing":"营销管理","cs":"客服管理",
 "report":"数据报表","org":"员工与权限","system":"系统设置",
}
rows=[]
for p in sorted(pathlib.Path("lib/api/https").glob("*.ts")):
    dom=p.stem
    src=p.read_text()
    # 每个方法一行或多行：  name: (args) => client.VERB(<path expr>, ...)
    for m in re.finditer(r'^\s{2}(\w+):\s*(\([^)]*\)|\w+)\s*=>\s*([\s\S]*?)(?=\n\s{2}\w+:|\n\};)', src, re.M):
        name, args, body = m.group(1), m.group(2), m.group(3)
        verbs = re.findall(r'client\.(get|post|put|patch|delete)\s*\(', body)
        paths = re.findall(r'[`"]((/api|/internal)[^`"]*)[`"]', body)
        if not verbs or not paths: continue
        # 条件路径（新增 vs 编辑）会有两个 path
        verb = verbs[0].upper()
        # 规范化模板变量：${x.foo} → {foo}
        norm=[]
        for pa in paths:
            pa=re.sub(r'\$\{[^}]*?\.?(\w+)\}', r'{\1}', pa)
            norm.append(pa)
        rows.append((dom, name, verb, norm))

by=collections.defaultdict(list)
for r in rows: by[r[0]].append(r)

out=[]
out.append("| # | 域 | 方法名 | HTTP | 端点 | 备注 |")
out.append("|---:|---|---|:---:|---|---|")
i=0
for dom in sorted(by, key=lambda d: -len(by[d])):
    for dom_,name,verb,paths in sorted(by[dom], key=lambda x:x[1]):
        i+=1
        if len(paths)==1:
            ep=f"`{paths[0]}`"; note=""
        else:
            ep=f"`{paths[-1]}`"; note=f"有业务键时改用 `{paths[0]}`（新增/编辑同方法）"
        out.append(f"| {i} | {DOMAIN_LABEL.get(dom_,dom_)} | `{name}` | {verb} | {ep} | {note} |")
print("\n".join(out))
print()
print("TOTAL", i)
cnt=collections.Counter(r[0] for r in rows)
print("BY_DOMAIN", dict(cnt))
pre=collections.Counter(re.match(r'(/api/[a-z-]+)', r[3][0]).group(1) for r in rows)
print("BY_PREFIX", dict(pre))
verbs=collections.Counter(r[2] for r in rows)
print("BY_VERB", dict(verbs))
