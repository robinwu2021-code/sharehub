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

# 完整码；中段允许 `*`（真源表写 `report:*:read` 覆盖一族资源）
FULL = r'[a-z_]+:(?:[a-z_]+|\*):[a-z_]+'


def declared_codes():
    """
    读真源表，展开两种简写。

    ① **承前省略**：`` `a:b:read` / `:create` `` —— `:create` 指 `a:b:create`，
       前缀取同一行前一个完整码的前两段。不展开会把一堆码误判成「表里没有」。

    ② **中段通配**：`` `report:*:read` `` —— 一行覆盖一族资源。
       2026-09-25 补：不认它的话，后端逐个强制的 report:device:read /
       report:finance:read / report:location:read / report:consumer:read
       会全被报成「强制未声明」，而真源表明明写了。
       这是本展开器的第二个解析盲区（第一个是 ①）。
       **通配保留原样返回**，由 `matches()` 在比对时做匹配 ——
       在这里展开成具体码做不到：真源表不知道 report 下有哪几个资源。
    """
    out = set()
    # 已取消的小节整段跳过。标题形如 `## 15. ~~多租户 / 平台~~ —— **已取消（2026-09-23）**`，
    # 底下那段说明里会把被取消的码逐个列出来解释「不再实现、不再登记」——
    # 而展开器照样把它们读成「声明」，于是 tenant:billing:read / dashboard:platform:read /
    # device:cabinet:assign 三条常年挂在「声明未强制」里。
    # **一段说自己已取消的文字，不该被当成声明。**（2026-09-25 补，本展开器第三个盲区。）
    cancelled = False
    for line in io.open(DOC, encoding='utf-8'):
        if line.startswith('#'):
            cancelled = '~~' in line and '取消' in line
        if cancelled:
            continue
        # **改名记录那一行也不是声明**（2026-09-26 补，本展开器第四个盲区）。
        # 形如「命名对齐记录（2026-07-29）：原 `workorder:wo:process` / `:audit` …
        # 从未被任何实现引用，现统一为 `:handle` / `:close`」——
        # 它在小节**内部**，所以上面那个「已取消小节整段跳过」接不住；
        # 而展开器照样把被淘汰的旧码读成声明。
        # 2026-09-26 工单三个端点改判新码后，强制侧没了、误读的声明侧还在，
        # 这两条旧码立刻变成「声明未强制」的假缺口 —— 一次修复把一个既有盲区顶了出来。
        if '命名对齐记录' in line or ('原 `' in line and '现统一为' in line):
            continue
        prefix = None
        for c in re.findall(r'`(%s|:[a-z_]+)`' % FULL, line):
            if c.startswith(':'):
                if prefix:
                    out.add(prefix + c)
            else:
                out.add(c)
                prefix = ':'.join(c.split(':')[:2])
    return out


def matches(declared, code):
    """声明码是否覆盖某个实际强制的码。支持中段通配 `a:*:c`。"""
    if declared == code:
        return True
    d, c = declared.split(':'), code.split(':')
    return len(d) == len(c) == 3 and d[0] == c[0] and d[2] == c[2] and d[1] == '*'


def enforced_codes():
    eps = json.loads(io.open(CONTRACT, encoding='utf-8').read())['endpoints']
    # 一个端点可能挂多个码（`can('a') or can('b')`），**逐个都算强制** ——
    # 只看 e['perm'] 会把第二个码算成「声明未强制」，而它其实正在放行。
    return {c for e in eps for c in (e.get('perms') or ([e['perm']] if e.get('perm') else []))
            if c and c != '*'}


def keys(declared, enforced):
    """
    两向比对。**不能用纯集合差** —— 声明侧可能带中段通配（`report:*:read`），
    它与任何 `report:<资源>:read` 都算对上，而集合差看不出这层关系。
    """
    out = set()
    for c in declared:
        if '*' in c:
            # 通配：只要覆盖到至少一个实际强制的码，就算"已强制"
            if not any(matches(c, e) for e in enforced):
                out.add('声明未强制 %s' % c)
        elif c not in enforced:
            out.add('声明未强制 %s' % c)
    for c in enforced:
        if not any(matches(d, c) for d in declared):
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
    # 与 keys() 同一套判据 —— **不能在这里另写一套集合差**：
    # 2026-09-25 就是这么出的 bug，keys() 改成认中段通配之后，
    # 这两行还是纯差集，于是报告里照旧列着 report:*:read 那一族，
    # 而台账比对已经不认为它们是缺口了。两处口径不一致，看报告的人永远对不上账。
    gaps = keys(declared, enforced)
    only_doc = sorted(c.split(' ', 1)[1] for c in gaps if c.startswith('声明未强制'))
    only_be = sorted(c.split(' ', 1)[1] for c in gaps if c.startswith('强制未声明'))

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
