#!/usr/bin/env python3
"""前端契约 vs 后端端点 一致性检查。

用途：前后端并行开发时，前端新增契约方法后端往往不知道。2026-07-30 实测漂移达 21%
（200 个契约里 42 个后端没有），全是前端两轮新功能。加此脚本让漂移可被发现。

用法（在 ops-web 下）：python3 scripts/check-backend-parity.py [--strict]
  --strict：有缺口时以非零码退出，可用于 CI 卡口。
"""
import re, sys, pathlib, collections

REPO = pathlib.Path(__file__).resolve().parents[2]
norm = lambda p: (re.sub(r'\{[^}]*\}', '{}', p).rstrip('/') or '/')

def backend_endpoints():
    out = set()
    for f in (REPO / "backend").rglob("*.java"):
        if "/test/" in str(f): continue
        src = f.read_text(errors="ignore")
        if "@RestController" not in src and "@Controller" not in src: continue
        m = re.search(r'@RequestMapping\(\s*"([^"]+)"', src)
        base = m.group(1) if m else ""
        for mm in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"?([^")\s]*)"?', src):
            sub = mm.group(2) or ""
            path = base + sub if sub.startswith("/") or not sub else f"{base}/{sub}"
            out.add((mm.group(1).upper(), norm(path.replace("//", "/"))))
        for mm in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\b(?!\()', src):
            out.add((mm.group(1).upper(), norm(base)))
    return out

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
miss = [(d, n, v, ps[-1]) for d, n, v, ps in fe
        if not (any((v, p) in be for p in ps) or any(p in be_paths for p in ps))]

print(f"前端契约 {len(fe)} · 后端端点 {len(be)} · 已对上 {len(fe)-len(miss)} · 缺 {len(miss)}"
      f"（覆盖率 {(len(fe)-len(miss))*100//max(len(fe),1)}%）")
if miss:
    print("\n后端缺口（前端已调用但后端没有）：")
    for d, c in collections.Counter(d for d, *_ in miss).most_common():
        print(f"  {d}: {c}")
    print()
    for d, n, v, p in miss:
        print(f"  {d:10s} {n:26s} {v:6s} {p}")
sys.exit(1 if (miss and "--strict" in sys.argv) else 0)
