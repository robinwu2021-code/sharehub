#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""权限码真源表 × 后端实际强制 —— 三方对齐里一直缺的第三条边。

## 为什么需要它

权限码的对齐此前查了三条边，唯独漏了这一条：

    前端契约 ↔ 后端端点      ops-web/scripts/check-backend-parity.py
    前端权限码 ↔ 后端权限码  ops-web/scripts/check-perm-parity.py
    菜单码 ↔ 后端码          backend/scripts/feature-align.py + MenuPermissionContractTest
    真源表 ↔ 后端码          ← 没有

CLAUDE.md 写着「用权限码前先确认它在此表中存在」，但那是**给人的叮嘱，不是卡口**。
于是 2026-09-24 实测：真源表声明 166 个码，后端端点强制 154 个，双向都在漂 ——
41 个声明了从不强制、29 个强制了表里没有（**连后端自己**都在用表里不存在的码）。
前端那 18 个幽灵码只是这摊漂移冒出水面的一角。

## ⚠️ 解析陷阱：真源表用承前省略的简写

    | 机柜 增/改 | `device:cabinet:create` / `:update` |

`:update` 是简写，指 `device:cabinet:update`。**不展开就会把一堆码误判成"表里没有"**
（本脚本作者第一版就栽在这，把 12 个该后端补闸的码错判成"前端自造"，
结论整个反了）。前缀取同一行里前一个完整码的前两段。

## 口径限制

「后端强制」取自 docs/api/contract.json 的 endpoint.perm，即**端点级** @PreAuthorize。
若有权限码只在 service 层校验，本脚本看不见，会误报成"声明了没强制"。
遇到这种就写进台账并注明。

用法（仓库根）：
  python3 backend/scripts/perm-ssot-align.py                  报告
  python3 backend/scripts/perm-ssot-align.py --strict          卡口（挂 check:drift）
  python3 backend/scripts/perm-ssot-align.py --write-baseline  生成台账
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(ROOT)
DOC = os.path.join(ROOT, 'docs/requirements/功能权限清单.md')
CONTRACT = os.path.join(ROOT, 'docs/api/contract.json')
BASELINE = os.path.join(ROOT, 'backend/known-perm-ssot-gaps.txt')

FULL = r'[a-z_]+(?::[a-z_]+){2}'


def declared_codes():
    """读真源表，展开承前省略的简写。"""
    out = set()
    for line in io.open(DOC, encoding='utf-8'):
        prefix = None
        for c in re.findall(r'`(%s|:[a-z_]+)`' % FULL, line):
            if c.startswith(':'):
                if prefix:
                    out.add(prefix + c)
            else:
                out.add(c)
                prefix = ':'.join(c.split(':')[:2])
    return out


def enforced_codes():
    eps = json.loads(io.open(CONTRACT, encoding='utf-8').read())['endpoints']
    return {e['perm'] for e in eps if e.get('perm') and e['perm'] != '*'}


def keys(declared, enforced):
    out = set()
    for c in declared - enforced:
        out.add('声明未强制 %s' % c)
    for c in enforced - declared:
        out.add('强制未声明 %s' % c)
    return out


def load_baseline():
    if not os.path.exists(BASELINE):
        return None
    return {l.strip() for l in io.open(BASELINE, encoding='utf-8')
            if l.strip() and not l.strip().startswith('#')}


def write_baseline(declared, enforced):
    hdr = [
        '# 权限码真源表 × 后端强制 已知缺口台账 —— **只准变短**',
        '#',
        '# 由 backend/scripts/perm-ssot-align.py --strict 执行（挂在 check:drift 下）。',
        '#',
        '# 【两类的含义】',
        '#   声明未强制 <码>  真源表写了这个能力，但没有任何端点用它鉴权 ——',
        '#                    要么后端漏了 @PreAuthorize（**能力没有闸**），',
        '#                    要么这条能力已取消而表没删（表在说谎）。',
        '#   强制未声明 <码>  后端拿它鉴权，但真源表里没有 —— 真源表不再是真源。',
        '#                    CLAUDE.md 专门点过这个复发问题。',
        '#',
        '# 【为什么允许有已知缺口】',
        '# 接入时双向合计 70 条。一次清完不现实，而"全绿才过"会让卡口常红，',
        '# 常红的卡口等于没有卡口。故冻结现状为基线，只拦新增。',
        '#',
        '# 【口径限制】',
        '# "后端强制"只看端点级 @PreAuthorize（contract.json 的 endpoint.perm）。',
        '# 只在 service 层校验的码会被误报成"声明未强制"，遇到请在此注明后登记。',
        '',
    ]
    body = sorted(keys(declared, enforced))
    io.open(BASELINE, 'w', encoding='utf-8').write('\n'.join(hdr + body) + '\n')
    print('已写入台账 %s（%d 条）' % (BASELINE, len(body)))


def main():
    declared, enforced = declared_codes(), enforced_codes()
    only_doc = sorted(declared - enforced)
    only_be = sorted(enforced - declared)

    print('真源表声明 %d · 后端端点强制 %d' % (len(declared), len(enforced)))
    print('\n① 真源表声明、后端从不强制   %d' % len(only_doc))
    for c in only_doc[:20]:
        print('   %s' % c)
    print('\n② 后端强制、真源表没有       %d' % len(only_be))
    for c in only_be[:20]:
        print('   %s' % c)

    if '--write-baseline' in sys.argv:
        write_baseline(declared, enforced)
        return 0
    if '--strict' not in sys.argv:
        return 0

    base = load_baseline()
    if base is None:
        print('\n❌ 缺少台账 %s' % BASELINE)
        print('   首次接入请生成：python3 backend/scripts/perm-ssot-align.py --write-baseline')
        return 1
    cur = keys(declared, enforced)
    new, fixed = sorted(cur - base), sorted(base - cur)
    rc = 0
    if new:
        print('\n❌ 新增缺口 %d 条（不在台账里）：' % len(new))
        for k in new:
            print('   %s' % k)
        rc = 1
    if fixed:
        print('\n✅ 已修复 %d 条，请从台账删掉这些行（只准变短）：' % len(fixed))
        for k in fixed:
            print('   %s' % k)
        rc = 1
    if rc == 0:
        print('\n✅ 真源表卡口通过：%d 条已知缺口，无新增。' % len(cur))
    return rc


if __name__ == '__main__':
    sys.exit(main())
