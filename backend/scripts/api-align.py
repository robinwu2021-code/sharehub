#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后端端点 × 前端调用 × 前端类型 三方对齐比对。

产出四类问题（前两类是真 bug，后两类是待办清单）：
  A. 前端在调、后端没有        → 运行期 404，**最高优先**
  B. 字段名/可空性对不上        → 运行期 undefined，比 404 更难查
  C. 后端有、前端没调           → 未接线（可能是待建页，也可能是废端点）
  D. 后端有、前端类型缺          → 前端拿到数据但没类型，写页面时会瞎猜

用法：python3 backend/scripts/api-align.py
依赖：先跑 api-extract.py 生成 /tmp/api_contract.json
"""
import io
import json
import os
import re
from collections import OrderedDict, Counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OPS = os.path.join(ROOT, 'ops-web')
CAPP = os.path.join(ROOT, 'c-app')


# ─────────────────────────── 前端：调了哪些路径 ───────────────────────────

def scan_frontend_calls():
    """从前端请求切片里抠出真实调用的路径与方法。

    路径常是模板串（`/api/ops/cabinets/${no}`），归一化成后端的 `{var}` 形式才能比对 ——
    否则两边永远匹配不上，比对结果全是假阳性。
    """
    calls = []
    for root, sub in ((OPS, 'lib/api/https'), (CAPP, 'src/api')):
        d = os.path.join(root, sub)
        if not os.path.isdir(d):
            continue
        for base, _, files in os.walk(d):
            for f in files:
                if not f.endswith('.ts') or f.endswith('.test.ts'):
                    continue
                p = os.path.join(base, f)
                s = io.open(p, encoding='utf-8').read()
                for m in re.finditer(
                        r'\b(get|post|put|del|delete|patch)\s*<[^>]*>\s*\(\s*[`"\']([^`"\']+)[`"\']',
                        s):
                    verb = {'del': 'DELETE'}.get(m.group(1), m.group(1).upper())
                    calls.append({'verb': verb, 'path': normalize(m.group(2)),
                                  'raw': m.group(2), 'file': os.path.relpath(p, ROOT)})
                for m in re.finditer(
                        r'\b(get|post|put|del|delete|patch)\s*\(\s*[`"\']([^`"\']+)[`"\']', s):
                    verb = {'del': 'DELETE'}.get(m.group(1), m.group(1).upper())
                    calls.append({'verb': verb, 'path': normalize(m.group(2)),
                                  'raw': m.group(2), 'file': os.path.relpath(p, ROOT)})
    seen, out = set(), []
    for c in calls:
        k = (c['verb'], c['path'])
        if k not in seen:
            seen.add(k)
            out.append(c)
    return out


def normalize(p):
    """`/api/x/${no}?a=1` → `/api/x/{}`；查询串一律丢掉（它不参与路由匹配）。"""
    p = p.split('?')[0]
    p = re.sub(r'\$\{[^}]*\}', '{}', p)
    p = re.sub(r'\{[^}]*\}', '{}', p)
    return p.rstrip('/') or '/'


# ─────────────────────────── 前端：类型定义 ───────────────────────────

def scan_frontend_types():
    """抽 ops-web `lib/types/*.ts` 的 interface / type 字面量字段。"""
    types = {}
    d = os.path.join(OPS, 'lib/types')
    if not os.path.isdir(d):
        return types
    for f in sorted(os.listdir(d)):
        if not f.endswith('.ts') or f.endswith('.test.ts'):
            continue
        s = io.open(os.path.join(d, f), encoding='utf-8').read()
        s = re.sub(r'//[^\n]*', '', s)
        s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
        for m in re.finditer(r'\b(?:export\s+)?(?:interface|type)\s+(\w+)\s*=?\s*\{', s):
            name = m.group(1)
            i, depth = m.end() - 1, 0
            while i < len(s):
                if s[i] == '{':
                    depth += 1
                elif s[i] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            body = s[m.end():i]
            fields = []
            for fm in re.finditer(r'(\w+)(\??)\s*:\s*([^;\n]+)', body):
                fields.append({'name': fm.group(1), 'optional': fm.group(2) == '?',
                               'type': fm.group(3).strip().rstrip(',')})
            if fields:
                types[name] = {'file': 'lib/types/' + f, 'fields': fields}
    return types


# ─────────────────────────── 比对 ───────────────────────────

def main():
    contract = json.load(io.open(os.path.join(ROOT, 'docs/api/contract.json'), encoding='utf-8'))
    eps = contract['endpoints']
    be = {(e['verb'], normalize(e['path'])): e for e in eps}
    calls = scan_frontend_calls()
    ftypes = scan_frontend_types()

    # A. 前端在调、后端没有
    missing = [c for c in calls if (c['verb'], c['path']) not in be]
    # C. 后端有、前端没调
    called = {(c['verb'], c['path']) for c in calls}
    unused = [e for k, e in be.items() if k not in called]

    # B/D. 出参形状 vs 前端类型：按 record 名同名匹配
    shape_issues, no_type = [], []
    checked = set()
    for e in eps:
        shape = e.get('responseShape')
        elem = (e.get('returnElement') or '').split('.')[-1]
        if not shape or not elem or elem in checked:
            continue
        checked.add(elem)
        ft = ftypes.get(elem)
        if not ft:
            no_type.append({'type': elem, 'endpoint': '%s %s' % (e['verb'], e['path'])})
            continue
        bf = {f['name'] for f in shape}
        ff = {f['name'] for f in ft['fields']}
        only_be, only_fe = sorted(bf - ff), sorted(ff - bf)
        if only_be or only_fe:
            shape_issues.append({'type': elem, 'file': ft['file'],
                                 'endpoint': '%s %s' % (e['verb'], e['path']),
                                 'backendOnly': only_be, 'frontendOnly': only_fe})

    out = {'missingInBackend': missing, 'notCalledByFrontend': unused,
           'shapeMismatch': shape_issues, 'noFrontendType': no_type}
    io.open('/tmp/api_align.json', 'w', encoding='utf-8').write(
        json.dumps(out, ensure_ascii=False, indent=1))

    print('后端端点 %d · 前端调用点 %d · 前端类型 %d' % (len(eps), len(calls), len(ftypes)))
    print('\nA. 前端在调、后端没有（运行期 404）        %d' % len(missing))
    for c in missing[:25]:
        print('   %-6s %-46s %s' % (c['verb'], c['raw'][:46], c['file']))
    print('\nB. 字段对不上（运行期 undefined）           %d' % len(shape_issues))
    for s in shape_issues[:25]:
        print('   %-22s %s' % (s['type'], s['endpoint']))
        if s['backendOnly']:
            print('        后端有前端无：%s' % ', '.join(s['backendOnly'][:8]))
        if s['frontendOnly']:
            print('        前端有后端无：%s' % ', '.join(s['frontendOnly'][:8]))
    print('\nC. 后端有、前端未调用                       %d' % len(unused))
    for p, c in Counter('/'.join(e['path'].split('/')[:3]) for e in unused).most_common(8):
        print('   %-26s %3d' % (p, c))
    print('\nD. 后端出参无对应前端类型                   %d' % len(no_type))
    for t in no_type[:15]:
        print('   %-26s %s' % (t['type'], t['endpoint']))
    write_md(missing, shape_issues, unused, no_type, len(eps), len(calls))
    print('\n明细 → /tmp/api_align.json · 文档 → docs/api/前后端对齐缺口.md')


def write_md(missing, shape_issues, unused, no_type, n_be, n_fe):
    out = os.path.join(ROOT, 'docs/api/前后端对齐缺口.md')
    L = ['# 前后端对齐缺口\n',
         '> **本文件由脚本生成，不要手改** —— '
         '`python3 backend/scripts/api-extract.py && python3 backend/scripts/api-align.py`。\n>',
         '> 比对三方：后端控制器实际端点（%d）× 前端请求切片实际调用（%d）× ops-web `lib/types` 类型。\n' % (n_be, n_fe),
         '按修复优先级排列 —— A 类是运行期 404，B 类是运行期 undefined（比 404 更难查，'
         '因为页面不报错、只是字段空白）。\n',
         '| 类 | 问题 | 数量 |\n|---|---|---|',
         '| A | 前端在调、后端没有 → **404** | %d |' % len(missing),
         '| B | 字段名对不上 → **undefined** | %d |' % len(shape_issues),
         '| C | 后端有、前端没调 | %d |' % len(unused),
         '| D | 后端出参无前端类型 | %d |' % len(no_type),
         '']

    L.append('## A. 前端在调、后端没有（%d）\n' % len(missing))
    L.append('前端已按目标形状写好请求，后端一行都没有 —— 页面目前全靠 mock 切片撑着。'
             '这些不是「设计待定」，路径和入参前端都定了，后端照着补即可。\n')
    by_file = OrderedDict()
    for c in missing:
        by_file.setdefault(c['file'], []).append(c)
    for f, cs in sorted(by_file.items()):
        L.append('**`%s`** — %d 个\n' % (f, len(cs)))
        L.append('| 方法 | 路径 |\n|---|---|')
        for c in cs:
            L.append('| %s | `%s` |' % (c['verb'], c['raw']))
        L.append('')

    L.append('## B. 字段对不上（%d）\n' % len(shape_issues))
    L.append('同名结构两边字段集不一致。「后端有前端无」= 前端拿到数据但没用（可能该展示却没展示）；'
             '「前端有后端无」= **前端读到 `undefined`**，页面显示空白且不报错。\n')
    L.append('| 结构 | 端点 | 后端有前端无 | 前端有后端无 |\n|---|---|---|---|')
    for s2 in sorted(shape_issues, key=lambda x: x['type']):
        L.append('| `%s` | `%s` | %s | %s |'
                 % (s2['type'], s2['endpoint'],
                    ' '.join('`%s`' % x for x in s2['backendOnly']) or '—',
                    ' '.join('`%s`' % x for x in s2['frontendOnly']) or '—'))
    L.append('')

    L.append('## C. 后端有、前端没调（%d）\n' % len(unused))
    L.append('两种可能，**必须逐条区分**：前端还没接（待办），或后端端点已废弃（该删）。'
             '混在一起会让「未接线」永远显得像「待办」。\n')
    L.append('| 方法 | 路径 | 落在 |\n|---|---|---|')
    for e in sorted(unused, key=lambda x: x['path']):
        L.append('| %s | `%s` | `%s` |' % (e['verb'], e['path'], e['handler']))
    L.append('')

    L.append('## D. 后端出参无对应前端类型（%d）\n' % len(no_type))
    L.append('| 结构 | 首个端点 |\n|---|---|')
    for t in sorted(no_type, key=lambda x: x['type']):
        L.append('| `%s` | `%s` |' % (t['type'], t['endpoint']))
    L.append('')

    io.open(out, 'w', encoding='utf-8').write('\n'.join(L) + '\n')


if __name__ == '__main__':
    main()
