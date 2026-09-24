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
                calls.extend(calls_in(s, os.path.relpath(p, ROOT)))
    seen, out = set(), []
    for c in calls:
        k = (c['verb'], c['path'])
        if k not in seen:
            seen.add(k)
            out.append(c)
    return out


# 泛型实参允许**嵌套**：`client.get<PageResult<RentOrder>>("/mp/trade/orders")` 这种写法，
# 用 `<[^>()]*>` 会在内层 `>` 处截断、整条调用匹配不上 —— 于是 c-app 明明调了，
# 报告里却说「前端未调用」。排除 `()` 而不排除 `>`：括号排除保证不会跨到下一个调用去。
# （与本文件上一条注释里那个 84% 误报是同一类错：正则把真实写法挡在外面。）
VERB_CALL = re.compile(r'\b(get|post|put|del|delete|patch)\s*(?:<[^()]*>)?\s*\(')
PATH_LIT = re.compile(r'[`"\'](/(?:api|internal|mp)/[^`"\']*)[`"\']')


def calls_in(src, relpath):
    """抠出一个前端文件里的 (verb, path)。

    **不要求路径字面量紧跟在 `(` 之后** —— 这是 2026-09-23 修掉的缺陷：
    原正则写的是 `\(\s*[`"\']`，于是

        client.post(x.accountNo ? `/api/x/${x.no}` : "/api/x", x)

    这种三元写法整条匹配不上，**两个路径一起漏报成「前端未调用」**。
    实测误报率 84%（运营端 113 条里 95 条是假的），而[实现状态总表] §六
    「131 条前端未接线」正是照抄这份输出 —— 按它排期会去做 95 条不存在的工作。

    改法：先用 verb 定位调用，再在**括号配平的实参区**里找出全部路径字面量。
    一次调用里有几个路径就记几个（三元两分支、拼接前缀，都算）。
    """
    out = []
    for m in VERB_CALL.finditer(src):
        verb = {'del': 'DELETE'}.get(m.group(1), m.group(1).upper())
        i, depth = m.end() - 1, 0
        while i < len(src):                     # 括号配平，取完整实参区
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        for pm in PATH_LIT.finditer(src[m.end():i]):
            out.append({'verb': verb, 'path': normalize(pm.group(1)),
                        'raw': pm.group(1), 'file': relpath})
    return out


def normalize(p):
    """`/api/x/${no}?a=1` → `/api/x/{}`；查询串一律丢掉（它不参与路由匹配）。"""
    p = p.split('?')[0]
    p = re.sub(r'\$\{[^}]*\}', '{}', p)
    p = re.sub(r'\{[^}]*\}', '{}', p)
    return p.rstrip('/') or '/'


# ─────────────────────────── 前端：类型定义 ───────────────────────────

def top_level_fields(body):
    """只取**顶层**字段，跳过嵌套对象字面量内部。

    2026-09-23 修：原来对整个 body 跑一遍正则，于是

        trend: { day: string; gmv: number; orders: number }[];
        todos: { pendingWorkOrders: number; pendingRefunds: number };

    里的 gmv / orders / pendingRefunds 都被当成了顶层字段，
    与后端顶层记录组件一比就报「前端有后端无」——**DashboardStats 因此凭空多出 4 条缺口，
    而它一个都不缺**。OperationOverview 的 `total`（在 geoReady 里）同理。
    """
    fields, depth, seg = [], 0, []
    for ch in body:
        if ch in '{[(':
            depth += 1
        elif ch in '}])':
            depth -= 1
        if depth == 0 and ch in ';\n':
            line = ''.join(seg)
            fm = re.match(r'\s*(\w+)(\??)\s*:\s*(.+)', line, re.S)
            if fm:
                fields.append({'name': fm.group(1), 'optional': fm.group(2) == '?',
                               'type': fm.group(3).strip().rstrip(',')})
            seg = []
        else:
            seg.append(ch)
    line = ''.join(seg)
    fm = re.match(r'\s*(\w+)(\??)\s*:\s*(.+)', line, re.S)
    if fm:
        fields.append({'name': fm.group(1), 'optional': fm.group(2) == '?',
                       'type': fm.group(3).strip().rstrip(',')})
    return fields


def scan_frontend_types(root=None, sub='lib/types'):
    """抽前端 `types` 目录里的 interface / type 字面量字段。

    **两端各有一套**：运营端 `ops-web/lib/types`、C 端 `c-app/src/types`。
    拿 ops-web 的类型去比 `/mp/*` 的出参，结果只能是「C 端类型全都缺」——
    比对对象错了，数字再准也没意义（同 C 类那 26 条的病根）。
    """
    types = {}
    d = os.path.join(root or OPS, sub)
    if not os.path.isdir(d):
        return types
    for f in sorted(os.listdir(d)):
        if not f.endswith('.ts') or f.endswith('.test.ts'):
            continue
        s = io.open(os.path.join(d, f), encoding='utf-8').read()
        s = re.sub(r'//[^\n]*', '', s)
        s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
        # `extends` 必须认：`export interface Venue extends Archivable {` 用
        # `\s*=?\s*\{` 是匹配不上的，于是**整个类型看不见** —— 它会被报成
        # 「后端出参无前端类型」(D)，而它明明就在那儿。实测 21 个带 extends 的接口里
        # 有 11 个是这么被误报的。
        for m in re.finditer(
                r'\b(?:export\s+)?(?:interface|type)\s+(\w+)\s*(?:extends\s+([^{=]+?))?\s*=?\s*\{', s):
            name = m.group(1)
            parents = [x.strip() for x in (m.group(2) or '').split(',') if x.strip()]
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
            fields = top_level_fields(body)
            if fields:
                types[name] = {'file': 'lib/types/' + f, 'fields': fields, 'parents': parents}

    # 继承来的字段要并进来，否则 `Venue extends Archivable` 会被判成「少了 archivedAt」——
    # 把一个修好的 D 类误报换成一个新的 B 类误报，等于没修。
    for name, t in types.items():
        seen, stack = set(), list(t.get('parents') or [])
        while stack:
            pname = stack.pop()
            if pname in seen or pname not in types:
                continue
            seen.add(pname)
            t['fields'] = t['fields'] + types[pname]['fields']
            stack.extend(types[pname].get('parents') or [])
    return types


# ─────────────────────────── 比对 ───────────────────────────

def main():
    contract = json.load(io.open(os.path.join(ROOT, 'docs/api/contract.json'), encoding='utf-8'))
    eps = contract['endpoints']
    be = {(e['verb'], normalize(e['path'])): e for e in eps}
    calls = scan_frontend_calls()
    ftypes = scan_frontend_types()                      # 运营端
    ctypes = scan_frontend_types(CAPP, 'src/types')     # C 端

    # A. 前端在调、后端没有
    missing = [c for c in calls if (c['verb'], c['path']) not in be]
    # C. 后端有、前端没调
    #
    # **先按受众分三类再数**，否则这个数字永远不可信：
    #   · /internal/* 是服务间调用（定时任务、网关、跨服务），**设计上就没有前端调用者** ——
    #     把它们算成「前端未接线」是拿错了比对对象，而且永远清不掉；
    #   · /mp/*  是 C 端端点，调用方是 c-app，不是 ops-web；
    #   · /api/* 才是运营端，未调用才真的可能是待办或废端点。
    # 混在一起数出来的 42，实际只有 16 条值得看。
    called = {(c['verb'], c['path']) for c in calls}
    unused_all = [e for k, e in be.items() if k not in called]
    unused = [e for e in unused_all if not e['path'].startswith('/internal/')]
    internal_unused = [e for e in unused_all if e['path'].startswith('/internal/')]

    # B/D. 出参形状 vs 前端类型
    #
    # **按名字直接相等匹配会数出一堆假的**，实测 83 条里只有 16 条是真的。三件事要先做：
    #   ① 比对对象按受众选：`/mp/*` 比 c-app 的类型，`/api/*` 比 ops-web 的，
    #      `/internal/*` 设计上就没有前端类型，不参与比对；
    #   ② 剥**分层后缀**再比：后端叫 `RoleRowVO` / `BrandEntry`，前端叫 `RoleRow` / `Brand` ——
    #      同一个概念，两层的命名习惯不同而已（35 条）；
    #   ③ **形状后缀不剥**：`CabinetDetail` / `InvoiceView` 是另一个投影，不是同一个类型。
    #      把它们映射到裸名去逐字段比，只会把 D 的假阳性换成 B 的假阳性，等于没修。
    LAYER_SUFFIX = ('VO', 'Entry', 'Row')
    SHAPE_SUFFIX = ('Detail', 'Brief', 'View', 'Result', 'Item')

    def resolve(name, pool):
        """→ (前端类型, 命中方式)；对不上返回 (None, 原因)。"""
        if name in pool:
            return pool[name], 'exact'
        for suf in LAYER_SUFFIX:
            if name.endswith(suf) and name[:-len(suf)] in pool:
                return pool[name[:-len(suf)]], 'layer:' + suf
        for suf in SHAPE_SUFFIX:
            if name.endswith(suf) and name[:-len(suf)] in pool:
                return None, 'shape:' + suf     # 另一个投影，不当缺失也不逐字段比
        return None, 'missing'

    shape_issues, no_type, other_projection, internal_types = [], [], [], []
    checked = set()
    for e in eps:
        shape = e.get('responseShape')
        elem = (e.get('returnElement') or '').split('.')[-1]
        if not shape or not elem or elem in checked:
            continue
        checked.add(elem)
        path = e['path']
        if path.startswith('/internal/'):
            internal_types.append({'type': elem, 'endpoint': '%s %s' % (e['verb'], path)})
            continue
        pool = ctypes if path.startswith('/mp/') else ftypes
        ft, how = resolve(elem, pool)
        if ft is None:
            rec = {'type': elem, 'endpoint': '%s %s' % (e['verb'], path),
                   'audience': 'c-app' if path.startswith('/mp/') else 'ops-web'}
            (other_projection if how.startswith('shape:') else no_type).append(rec)
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
    write_md(missing, shape_issues, unused, no_type, len(eps), len(calls), internal_unused,
             other_projection, internal_types)
    print('\n明细 → /tmp/api_align.json · 文档 → docs/api/前后端对齐缺口.md')


def write_md(missing, shape_issues, unused, no_type, n_be, n_fe, internal_unused=(),
             other_projection=(), internal_types=()):
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
    L.append('**已排除 `/internal/*`**（%d 条）：那是服务间调用，设计上就没有前端调用者，'
             '算进来只会让这个数字永远清不掉。\n' % len(internal_unused))
    L.append('仍需**逐条区分**：前端还没接（待办），或后端端点已废弃（该删）。'
             '混在一起会让「未接线」永远显得像「待办」。'
             '按受众分开看 —— `/api/*` 归运营端 ops-web，`/mp/*` 归 C 端 c-app。\n')
    for title, pref in (('运营端 `/api/*`', '/api/'), ('C 端 `/mp/*`', '/mp/')):
        rows = [e for e in unused if e['path'].startswith(pref)]
        if not rows:
            continue
        L.append('### %s（%d）\n' % (title, len(rows)))
        L.append('| 方法 | 路径 | 落在 |\n|---|---|---|')
        for e in sorted(rows, key=lambda x: x['path']):
            L.append('| %s | `%s` | `%s` |' % (e['verb'], e['path'], e['handler']))
        L.append('')
    other = [e for e in unused if not e['path'].startswith('/api/') and not e['path'].startswith('/mp/')]
    if other:
        L.append('### 其它（%d）\n' % len(other))
        L.append('| 方法 | 路径 | 落在 |\n|---|---|---|')
        for e in sorted(other, key=lambda x: x['path']):
            L.append('| %s | `%s` | `%s` |' % (e['verb'], e['path'], e['handler']))
        L.append('')

    L.append('## D. 后端出参无对应前端类型（%d）\n' % len(no_type))
    L.append('**已排除三类**（合计 %d 条），它们不是缺口：\n' % (len(internal_types) + len(other_projection)))
    L.append('- `/internal/*` 的出参 %d 条 —— 服务间调用，设计上就没有前端类型；' % len(internal_types))
    L.append('- **另一个投影** %d 条 —— `CabinetDetail` / `InvoiceView` 这类，'
             '前端有对应的裸类型但形状本就不同，硬当同名去逐字段比只会造出假的字段差异；'
             % len(other_projection))
    L.append('- **分层后缀**（`VO` / `Entry` / `Row`）—— 后端 `RoleRowVO`、前端 `RoleRow`，'
             '同一概念两层命名习惯不同，已自动对齐后按字段比（结果进 B 类）。\n')
    L.append('下表按**受众**分开：`/api/*` 比 ops-web 的类型，`/mp/*` 比 c-app 的。\n')
    for aud, title in (('ops-web', '运营端 ops-web'), ('c-app', 'C 端 c-app')):
        rows = [t for t in no_type if t.get('audience') == aud]
        if not rows:
            continue
        L.append('### %s（%d）\n' % (title, len(rows)))
        L.append('| 结构 | 首个端点 |\n|---|---|')
        for t in sorted(rows, key=lambda x: x['type']):
            L.append('| `%s` | `%s` |' % (t['type'], t['endpoint']))
        L.append('')
    rest = [t for t in no_type if t.get('audience') not in ('ops-web', 'c-app')]
    if rest:
        L.append('### 其它（%d）\n' % len(rest))
        L.append('| 结构 | 首个端点 |\n|---|---|')
        for t in sorted(rest, key=lambda x: x['type']):
            L.append('| `%s` | `%s` |' % (t['type'], t['endpoint']))
        L.append('')

    io.open(out, 'w', encoding='utf-8').write('\n'.join(L) + '\n')


if __name__ == '__main__':
    main()
