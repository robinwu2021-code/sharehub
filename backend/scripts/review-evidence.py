#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能评审证据包：按阶段把每个服务端功能涉及的类、表、端点、测试、棘轮台账条目切出来。

为什么要有：评审结论必须有出处。手工翻 1100 个类、534 个端点、23 份台账去找「这个功能涉及什么」，
漏一处就会把真问题评成「无问题」。证据由脚本切，评审人只做判断。

真值来源（都不手抄）：
  · 功能 → 目标包：docs/requirements/服务端功能清单.md 每个功能的元数据行
  · 阶段 → 功能：docs/technical/功能评审/00-任务规划.md 的阶段明细表
  · 类 → 目标包：gen-code-inventory.py 的归位规则（与代码清单同一套）
  · 端点：docs/api/contract.json；表：实体 @TableName；台账：backend/known-*.txt

局限（写在产物头部，避免被当成全集）：
  · 同一目标包下的多个功能共享证据（例如 network.site 下的站点、坪效、找柜）；
  · 测试按「文件里出现类名或端点路径」匹配，是候选而非覆盖率；
  · 台账按类名、表名、路径做子串匹配，个别短名可能误命中。

用法：python3 backend/scripts/review-evidence.py S1        # 写 docs/technical/功能评审/证据/S1-证据包.md
"""
import glob
import importlib.util
import io
import json
import os
import re
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SPEC = importlib.util.spec_from_file_location('inv', os.path.join(HERE, 'gen-code-inventory.py'))
inv = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inv)

FEATURES = os.path.join(ROOT, 'docs/requirements/服务端功能清单.md')
PLAN = os.path.join(ROOT, 'docs/technical/功能评审/00-任务规划.md')
CONTRACT = os.path.join(ROOT, 'docs/api/contract.json')
OUT_DIR = os.path.join(ROOT, 'docs/technical/功能评审/证据')

META = re.compile(r'^#### (SF-[A-Z]+-\d+) (.+)$\n> 包 `([^`]+)` · (\S+ \S+) · (\S+?) · 菜单 (.*?) · 需求 (.*)$', re.M)
TABLE_RE = re.compile(r'@TableName\(\s*(?:value\s*=\s*)?"([a-z0-9_]+)"')


def features():
    s = io.open(FEATURES, encoding='utf-8').read()
    return OrderedDict((m.group(1), dict(name=m.group(2), pkg=m.group(3), st=m.group(4), ph=m.group(5),
                                         menu=m.group(6), req=m.group(7))) for m in META.finditer(s))


def stages():
    s = io.open(PLAN, encoding='utf-8').read()
    out = OrderedDict()
    for m in re.finditer(r'^\*\*(S\d+) ([^*]+)\*\*\n\n((?:\|.*\n)+)', s, re.M):
        out[m.group(1)] = (m.group(2), re.findall(r'\| (SF-[A-Z]+-\d+) ', m.group(3)))
    return out


def classes():
    """[(maven, 现包, 类名, 目标包, 源码)]；接入层控制器按 PORTAL 的主域归属（不考虑跨模块留单体）。"""
    rows = []
    for mod, rel, cls, src in inv.scan():
        t = inv.target_of(mod, rel, cls, src)
        if not t:
            continue
        pkg = t[0]
        if mod == 'sharehub-app' and rel.startswith('portal') and cls in inv.PORTAL:
            pkg = inv.PORTAL[cls][0].replace('app.', '')
        rows.append((mod, rel.replace('/', '.'), cls, pkg, src))
    return rows


def tests():
    out = []
    for f in glob.glob(os.path.join(ROOT, 'backend/*/src/test/java/**/*.java'), recursive=True):
        out.append((os.path.relpath(f, ROOT), io.open(f, encoding='utf-8').read()))
    return out


def ledgers():
    out = OrderedDict()
    for f in sorted(glob.glob(os.path.join(ROOT, 'backend/known-*.txt'))):
        lines = [l.rstrip() for l in io.open(f, encoding='utf-8') if l.strip() and not l.lstrip().startswith('#')]
        out[os.path.basename(f)] = lines
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit('用法：review-evidence.py S<n>')
    st = sys.argv[1]
    F, S = features(), stages()
    if st not in S:
        sys.exit('未知阶段 %s；可选：%s' % (st, ' '.join(S)))
    title, ids = S[st]
    C, T, K = classes(), tests(), ledgers()
    eps = json.load(io.open(CONTRACT, encoding='utf-8'))['endpoints']

    L = ['# %s %s · 证据包' % (st, title), '',
         '> **本文件由脚本生成，勿手改** —— `python3 backend/scripts/review-evidence.py %s`。' % st,
         '> 局限：同一目标包的功能共享证据；测试与台账按名称子串匹配，是候选不是全集。', '']
    for fid in ids:
        f = F[fid]
        pkg = f['pkg']
        mine = [c for c in C if c[3] == pkg or c[3].startswith(pkg + '.')]
        names = sorted({c[2] for c in mine})
        tables = sorted({t for c in mine for t in TABLE_RE.findall(c[4])})
        ctrl = {c[2] for c in mine if inv.CONTROLLER_RE.search(c[4])}
        my_eps = [e for e in eps if e['handler'].split('#')[0] in ctrl]
        paths = sorted({e['path'] for e in my_eps})
        keys = [n for n in names if len(n) > 6] + tables + [p for p in paths if p.count('/') >= 3]
        my_tests = sorted({p for p, src in T if any(k in src for k in keys)})
        hits = OrderedDict()
        for name, lines in K.items():
            h = [l for l in lines if any(k in l for k in keys)]
            if h:
                hits[name] = h

        L.append('## %s %s' % (fid, f['name']))
        L.append('')
        L.append('> 包 `%s` · %s · %s · 菜单 %s · 需求 %s' % (pkg, f['st'], f['ph'], f['menu'], f['req']))
        L.append('')
        if not mine:
            L.append('**无现有代码**（目标包下没有类）。')
            L.append('')
            continue
        L.append('**类（%d）**' % len(mine))
        L.append('')
        L.append('| 现位置 | 目标包 | 类 |')
        L.append('|---|---|---|')
        grp = OrderedDict()
        for mod, cur, cls, tp, _ in sorted(mine, key=lambda x: (x[3], x[0], x[1], x[2])):
            grp.setdefault((mod.replace('sharehub-', ''), cur, tp), []).append(cls)
        for (mod, cur, tp), cs in grp.items():
            L.append('| `%s` · `%s` | `%s` | %s |' % (mod, cur or '(根)', tp, ' · '.join(cs)))
        L.append('')
        L.append('**表（%d）**：%s' % (len(tables), ' · '.join('`%s`' % t for t in tables) or '—'))
        L.append('')
        L.append('**端点（%d）**' % len(my_eps))
        L.append('')
        if my_eps:
            L.append('| 方法 | 路径 | 权限码 | 处理器 |')
            L.append('|---|---|---|---|')
            for e in sorted(my_eps, key=lambda e: (e['path'], e['verb'])):
                L.append('| %s | `%s` | %s | `%s` |' % (e['verb'], e['path'], '`%s`' % e['perm'] if e.get('perm') else '—',
                                                      e['handler']))
            L.append('')
        L.append('**测试候选（%d）**：%s' % (len(my_tests), ' · '.join('`%s`' % os.path.basename(t) for t in my_tests) or '—'))
        L.append('')
        n = sum(len(v) for v in hits.values())
        L.append('**棘轮台账命中（%d）**' % n)
        L.append('')
        if hits:
            for name, h in hits.items():
                L.append('- `%s`（%d）' % (name, len(h)))
                for l in h:
                    L.append('  - `%s`' % l.replace('`', "'")[:200])
        else:
            L.append('—')
        L.append('')

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, '%s-证据包.md' % st)
    io.open(out, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('✓ %s' % os.path.relpath(out, ROOT))


if __name__ == '__main__':
    main()
