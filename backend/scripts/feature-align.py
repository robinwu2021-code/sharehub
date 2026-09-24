#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能矩阵 × 前端页面 × 服务端 API 三方对齐。

## 与 api-align.py 的分工

`api-align.py` 比的是**后端端点 × 前端调用 × 前端类型** —— 它回答
「这个接口有没有人调、字段对不对得上」。

本脚本比的是更上一层：**菜单叶 × 页面 × 权限码**，回答的是
「菜单上写着的功能，究竟有没有页面、页面背后有没有后端」。

两者缺一不可：api-align 绿不代表功能存在（端点可以建了但菜单上没入口），
本脚本绿也不代表能用（页面可以在但调的接口 404）。

## 三个真源

  功能矩阵 → `ops-web/lib/nav.ts` 的 NAV（[运营端功能清单] §三 由它导出合成，非手抄）
  前端页面 → `ops-web/app/**/page.tsx` 的路由
  服务端 API → `docs/api/contract.json`（由 api-extract.py 从 @RequestMapping 提取）

## 查四类不一致

  ① 菜单叶指向的页面不存在        → 点了 404（前端路由级）
  ② 菜单叶声明的权限码后端无人用  → 码写错了，或后端端点漏了 @PreAuthorize
  ③ 后端权限码在菜单上无处可达    → 能力建了但用户找不到入口
  ④ 页面存在但不在菜单上          → 孤儿页（可能是深链目标，需人工判）

用法：`python3 scripts/feature-align.py`
"""
import io
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(ROOT)          # 仓库根
OPS = os.path.join(ROOT, 'ops-web')


def nav_leaves():
    """从 nav.ts 抠出菜单叶：(href, label, perm, soon)。

    只认对象字面量里同时带 href 与 label 的那些 —— section 头也有 href，
    但它没有 perm，靠这个区分。
    """
    src = io.open(os.path.join(OPS, 'lib/nav.ts'), encoding='utf-8').read()
    src = re.sub(r'//[^\n]*', '', src)
    out = []
    for m in re.finditer(r'\{([^{}]*href\s*:[^{}]*)\}', src):
        body = m.group(1)
        href = re.search(r'href\s*:\s*[`"\']([^`"\']+)', body)
        label = re.search(r'label\s*:\s*["\']([^"\']+)', body)
        perm = re.search(r'perm\s*:\s*["\']([^"\']+)', body)
        if not href or not label:
            continue
        out.append({'href': href.group(1), 'label': label.group(1),
                    'perm': perm.group(1) if perm else None,
                    'soon': 'soon: true' in body or 'soon:true' in body})

    # 运营管理那一组的叶子由 opLeaves([[page, label, perm, group], ...]) **动态生成**，
    # href 是模板串 `/operation/${page}`，对象字面量正则解不开。
    # 不补这段，12 个 /operation/* 页面会被误报成「孤儿页」——
    # 而它们明明就在菜单上。真源是那张元组表，按它解析。
    for arr in re.findall(r'opLeaves\(\[(.*?)\]\s*\)', src, re.S):
        for row in re.finditer(r'\[\s*"([\w-]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\]', arr):
            out.append({'href': '/operation/' + row.group(1), 'label': row.group(2),
                        'perm': row.group(3) or None, 'soon': False})
    return out


def nav_modules():
    """section 声明的模块前缀（`module: "cs"` 与 `modules: ["location", ...]`）。

    **必须一起算** —— 菜单叶可以完全不写 perm（如 `{ href: "/cs", label: "报障受理" }`），
    它的可见性由 section 的 module 经 `canModule` 决定。只看叶子的 perm，
    会把整个 cs / marketing 模块误报成「菜单上无入口」。
    前端 `visibleSections` 的真实规则就是 module 与 perm 两层，这里照着它算。
    """
    src = io.open(os.path.join(OPS, 'lib/nav.ts'), encoding='utf-8').read()
    src = re.sub(r'//[^\n]*', '', src)
    mods = set(re.findall(r'\bmodule\s*:\s*["\']([\w-]+)["\']', src))
    for arr in re.findall(r'\bmodules\s*:\s*\[([^\]]*)\]', src):
        mods |= set(re.findall(r'["\']([\w-]+)["\']', arr))
    return mods


def page_routes():
    """app/**/page.tsx → 路由。`app/foo/page.tsx` → `/foo`，`app/page.tsx` → `/`。"""
    base = os.path.join(OPS, 'app')
    routes = set()
    for dp, _, fns in os.walk(base):
        if 'page.tsx' in fns:
            rel = os.path.relpath(dp, base).replace(os.sep, '/')
            routes.add('/' if rel == '.' else '/' + rel)
    return routes


def backend_perms():
    """contract.json 里所有端点声明的权限码 → 端点数。"""
    c = json.load(io.open(os.path.join(ROOT, 'docs/api/contract.json'), encoding='utf-8'))
    d = defaultdict(list)
    for e in c['endpoints']:
        if e.get('perm'):
            d[e['perm']].append('%s %s' % (e['verb'], e['path']))
    return d


def main():
    leaves = nav_leaves()
    routes = page_routes()
    beperms = backend_perms()

    # 扫描面有效性：任一为空说明解析坏了，而不是「没有问题」
    assert leaves, 'nav.ts 一个菜单叶都没解析到 —— 解析坏了'
    assert routes, 'app/ 一个页面都没找到'
    assert beperms, 'contract.json 一个权限码都没有'

    print('菜单叶 %d · 前端页面 %d · 后端权限码 %d\n' % (len(leaves), len(routes), len(beperms)))

    # ① 菜单指向的页面不存在
    bad_route = []
    for lf in leaves:
        path = lf['href'].split('?')[0].rstrip('/') or '/'
        if path not in routes:
            bad_route.append(lf)
    print('① 菜单叶指向的页面不存在（点了 404）       %d' % len(bad_route))
    for lf in bad_route:
        print('   %-34s %s' % (lf['href'], lf['label']))

    # ② 菜单声明的权限码，后端没有任何端点用
    navperms = {lf['perm'] for lf in leaves if lf['perm']}
    orphan_nav = sorted(p for p in navperms if p not in beperms)
    print('\n② 菜单权限码在后端无人使用                 %d' % len(orphan_nav))
    for p in orphan_nav:
        who = [lf['label'] for lf in leaves if lf['perm'] == p]
        print('   %-38s ← %s' % (p, ' / '.join(who[:3])))

    # ③ 后端有某个资源的端点，而菜单上这个资源一个入口都没有
    #
    # **按 `模块:资源` 归组，不按完整权限码比** —— 菜单叶声明的一律是 `:read`
    # （"能不能看见这个入口"），而 `:create` / `:update` / `:approve` 是**页内动作**的码，
    # 本来就不该出现在菜单上。按完整码比会报出 97 条，其中绝大多数是这个设计造成的假信号。
    # 真正该报的是：整个资源在菜单上无处可达。
    def res(p):
        bits = p.split(':')
        return ':'.join(bits[:2]) if len(bits) >= 2 else p

    nav_res = {res(p) for p in navperms}
    nav_mods = nav_modules()
    assert nav_mods, 'nav.ts 一个 section module 都没解析到'
    be_res = {}
    for p, eps in beperms.items():
        be_res.setdefault(res(p), []).extend(eps)
    # 资源可达 = 它的完整 `模块:资源` 在某个叶子的 perm 上，**或**它的模块在某个 section 上
    orphan_be = sorted(r for r in be_res
                       if r not in nav_res and r.split(':')[0] not in nav_mods)
    print('\n③ 后端整个资源在菜单上无入口               %d' % len(orphan_be))
    for r in orphan_be:
        print('   %-34s (%d 个端点，如 %s)' % (r, len(be_res[r]), be_res[r][0]))

    # ④ 页面不在菜单上
    navroutes = {lf['href'].split('?')[0].rstrip('/') or '/' for lf in leaves}
    orphan_page = sorted(r for r in routes if r not in navroutes and not r.startswith('/dev'))
    print('\n④ 页面存在但不在菜单上（孤儿页/深链目标）  %d' % len(orphan_page))
    for r in orphan_page:
        print('   %s' % r)

    json.dump({'badRoute': bad_route, 'orphanNavPerm': orphan_nav,
               'orphanBackendResource': {r: be_res[r] for r in orphan_be},
               'orphanPage': orphan_page},
              io.open('/tmp/feature_align.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('\n明细 → /tmp/feature_align.json')

    if '--strict' not in sys.argv:
        return 0

    # ── 卡口 ──
    # ①③ 零容忍：菜单指向不存在的页面 = 点了 404；后端整个资源在菜单上无入口 =
    #    功能建好了没人找得到。两者都没有「暂时接受」的余地，且当前都是 0。
    # ②   不在这里判 —— 它由 backend/known-menu-perm-mismatch.txt +
    #     MenuPermissionContractTest 管着（那是棘轮台账，有产品裁决记录）。
    #     两处都判会让同一条缺口报两次，且台账改了这边不改就自相矛盾。
    # ④   孤儿页多为深链目标（/login、/apply/status 之类），是正常的，故走基线。
    rc = 0
    if bad_route:
        print('\n❌ ① 菜单叶指向的页面不存在（点了 404）：%d 条' % len(bad_route))
        rc = 1
    if orphan_be:
        print('\n❌ ③ 后端整个资源在菜单上无入口：%d 条' % len(orphan_be))
        rc = 1

    base_f = os.path.join(ROOT, 'backend/known-orphan-pages.txt')
    base = set()
    if os.path.exists(base_f):
        base = {l.strip() for l in io.open(base_f, encoding='utf-8')
                if l.strip() and not l.strip().startswith('#')}
    new = sorted(set(orphan_page) - base)
    gone = sorted(base - set(orphan_page))
    if new:
        print('\n❌ ④ 新增孤儿页（不在菜单上，也不在台账里）：')
        for r in new:
            print('   %s' % r)
        print('\n   要么给它加菜单入口，要么写进 %s 并说明它是深链目标。' % base_f)
        rc = 1
    if gone:
        print('\n✅ ④ 这些页面已不再是孤儿，请从台账删掉：')
        for r in gone:
            print('   %s' % r)
        rc = 1
    if rc == 0:
        print('\n✅ 功能对齐卡口通过。')
    return rc


if __name__ == '__main__':
    sys.exit(main())
