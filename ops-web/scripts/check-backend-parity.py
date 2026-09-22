#!/usr/bin/env python3
"""前端契约 vs 后端端点 一致性检查。

用途：前后端并行开发时，前端新增契约方法后端往往不知道。2026-07-30 实测漂移达 21%
（200 个契约里 42 个后端没有），全是前端两轮新功能。加此脚本让漂移可被发现。

用法（在 ops-web 下）：python3 scripts/check-backend-parity.py [--strict]
  --strict：有缺口时以非零码退出，可用于 CI 卡口。
"""
import json, re, sys, pathlib, collections

REPO = pathlib.Path(__file__).resolve().parents[2]
norm = lambda p: (re.sub(r'\{[^}]*\}', '{}', p).rstrip('/') or '/')

def backend_endpoints():
    """读 docs/api/contract.json —— 后端端点的唯一真值。

    本函数原来自己用正则扫 Java。同一个问题有两套口径就必然给出两个答案：实测这边数出 267、
    backend/scripts/api-extract.py 数出 268（后者与 `@*Mapping` 注解出现次数逐一核对过），
    于是「后端到底有多少端点」无法回答。改为共用一份抽取结果，本脚本只管比对与卡口。

    真值文件由后端侧生成：`python3 backend/scripts/api-extract.py`。
    """
    p = REPO / "docs/api/contract.json"
    if not p.exists():
        sys.exit("缺少 %s —— 先在仓库根跑 `python3 backend/scripts/api-extract.py` 生成。" % p)
    data = json.loads(p.read_text())
    return {(e["verb"], norm(e["path"])) for e in data["endpoints"]}

def frontend_contracts():
    out = []
    for f in sorted((REPO / "ops-web/lib/api/https").glob("*.ts")):
        src = f.read_text()
        for m in re.finditer(r'^\s{2}(\w+):\s*(\([^)]*\)|\w+)\s*=>\s*([\s\S]*?)(?=\n\s{2}\w+:|\n\};)', src, re.M):
            body = m.group(3)
            verbs = re.findall(r'client\.(get|post|put|patch|delete)\s*\(', body)
            raw = re.findall(r'[`"]((?:/api|/internal)[^`"]*)[`"]', body)
            paths = [norm(re.sub(r'\$\{[^}]*?\.?(\w+)\}', '{}', x if isinstance(x, str) else x[0])) for x in raw]
            if verbs and paths:
                out.append((f.stem, m.group(1), verbs[0].upper(), paths))
    return out

be = backend_endpoints()
be_paths = {p for _, p in be}
fe = frontend_contracts()

# 匹配判据是 **(动词, 路径) 联合键**。
#
# 此前还有一条 path-only 回退（`any(p in be_paths ...)`），只要路径存在就算对上 ——
# 于是 `POST /api/ops/cabinets` 被同路径的 `GET` 判为「已对上」，真实缺口被吞掉，
# 历次报出的覆盖率**系统性偏乐观**（实测至少漏报 saveCabinet 一例）。
# 现在改为严格匹配，并把「路径在、动词不符」单独列成一节 ——
# 它与「端点完全没有」是两种不同的修法（前者通常是动作名对不齐，后者要新写端点），
# 混在一起报会让人以为要新写一堆端点。
matched, verb_mismatch, missing = [], [], []
for d, n, v, ps in fe:
    if any((v, p) in be for p in ps):
        matched.append((d, n, v, ps[-1]))
    elif any(p in be_paths for p in ps):
        have = sorted({bv for bv, bp in be if bp in set(ps)})
        verb_mismatch.append((d, n, v, ps[-1], ",".join(have)))
    else:
        missing.append((d, n, v, ps[-1]))

gap = len(verb_mismatch) + len(missing)
print(f"前端契约 {len(fe)} · 后端端点 {len(be)} · 已对上 {len(matched)} · "
      f"缺 {len(missing)} · 动词不符 {len(verb_mismatch)}"
      f"（严格覆盖率 {len(matched)*100//max(len(fe),1)}%）")

if verb_mismatch:
    print("\n路径存在但动词不符（多半是动作名没对齐，改一侧命名即可）：")
    for d, n, v, p, have in verb_mismatch:
        print(f"  {d:10s} {n:26s} 前端 {v:6s} 后端有 [{have}]  {p}")

if missing:
    print("\n后端缺口（前端已调用，后端连路径都没有）：")
    for d, c in collections.Counter(d for d, *_ in missing).most_common():
        print(f"  {d}: {c}")
    print()
    for d, n, v, p in missing:
        print(f"  {d:10s} {n:26s} {v:6s} {p}")

sys.exit(1 if (gap and "--strict" in sys.argv) else 0)
