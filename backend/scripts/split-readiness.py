#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拆分就绪度：盘点**所有**跨服务的类引用，产出 Port 工作清单（ADR-017 S3）。

与 `arch-guard.py` 的 G3 的区别很关键：
  · G3 只查「注入了别的服务的 ServiceImpl/Mapper」—— 那是**纪律**层面的检查；
  · 本脚本查**任何跨服务的 import**（实体/DTO/接口/工具类都算）—— 那是**能不能编译**的检查。

真拆成 Maven 模块时，`svc-core` 里出现一行 `import ...platform.loc.entity.LocSite` 就编译不过。
所以 G3 报 0 不代表能拆，本脚本报 0 才代表能拆。

用法：python3 backend/scripts/split-readiness.py
"""
import io
import os
import re
from collections import defaultdict, Counter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# 扫描根覆盖**全部**模块：svc-* 抽出去后若只扫 app，跨服务引用会「消失」——
# 又是一次「报绿但什么都没扫」。本会话已三次遇到这个模式（db_columns 假绿、
# 更名后脚本路径失效、这里），所以每次动模块结构都必须回头检查扫描根。
MODULE_ROOTS = [os.path.join(ROOT, 'backend', m, 'src/main/java/ai/neargo/sharehub')
                for m in ('sharehub-app', 'sharehub-svc-gateway', 'sharehub-svc-finance', 'sharehub-svc-platform', 'sharehub-svc-ops', 'sharehub-svc-core')]
MODULE_ROOTS = [p for p in MODULE_ROOTS if os.path.isdir(p)]
BASE_PKG = 'ai.neargo.sharehub.'

# ── 包 → 服务。按**最长前缀优先**匹配，故顺序无关但需写全子包例外 ──
#
# 归属依据是 module-graph.py 的 SERVICES（表 → 模块 → 服务），这里翻译成包路径。
# 子包例外是真实存在的：`trade` 大体属 core，但 `trade/finance` 是 finance；
# `user` 属 core，但 `user/cs` 是 ops。**不写这些例外会把跨服务引用统计成内部引用**，
# 得出「已经可以拆」的错误结论。
PKG_SVC = [
    ('finance', 'finance'),   # 已由 trade.finance 正名为顶层包，避免跨 jar 分裂包
    ('cs', 'ops'),        # 已由 user.cs 正名为顶层包
    ('alarm', 'ops'),     # 已由 dev.alarm 正名为顶层包
    ('agent', 'platform'),
    ('loc', 'platform'),
    ('platform', 'platform'),
    ('gw', 'device-gateway'),
    ('dev', 'core'),
    ('inv', 'core'),
    ('trade', 'core'),
    ('user', 'core'),
    ('wo', 'ops'),
]

# 这些不参与服务归属：
#   api      —— 跨服务接口层，谁都能依赖（就是为此存在的）
#   common   —— 已抽成独立模块
#   config   —— 留在 app（ADR-017：app-* 持有配置与装配）
#   portal   —— 控制器层，S4 拆 app-* 时按前缀分派，不属任何 svc
#   dto/seed —— 早期共享 DTO 与种子数据，属遗留待清理
NEUTRAL = ('api', 'common', 'auth', 'config')
DEFERRED = ('portal', 'dto', 'seed', 'report')


def svc_of_pkg(pkg):
    """包路径 → 服务。返回 None 表示中立/待定。"""
    if pkg.startswith(NEUTRAL):
        return None
    if pkg.startswith(DEFERRED):
        return None
    for prefix, svc in PKG_SVC:
        if pkg == prefix or pkg.startswith(prefix + '.'):
            return svc
    return None


def main():
    edges = defaultdict(list)     # (fromSvc, toSvc) → [(file, import)]
    by_target_pkg = Counter()     # 被跨服务引用最多的包
    files_scanned = 0

    for root in MODULE_ROOTS:
      for base, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith('.java'):
                continue
            p = os.path.join(base, f)
            rel = os.path.relpath(p, root)
            my_pkg = os.path.dirname(rel).replace(os.sep, '.')
            mine = svc_of_pkg(my_pkg)
            if not mine:
                continue
            files_scanned += 1
            for line in io.open(p, encoding='utf-8'):
                line = line.strip()
                if not line.startswith('import ' + BASE_PKG):
                    continue
                imported = line[len('import '):].rstrip(';')
                tail = imported[len(BASE_PKG):]
                target_pkg = '.'.join(tail.split('.')[:-1])   # 去掉类名
                theirs = svc_of_pkg(target_pkg)
                if theirs and theirs != mine:
                    edges[(mine, theirs)].append((rel, tail))
                    by_target_pkg[target_pkg] += 1

    total = sum(len(v) for v in edges.values())
    print('扫描 %d 个已归属服务的类，跨服务引用 **%d 处**\n' % (files_scanned, total))

    if not total:
        print('✅ 无跨服务直接引用 —— 可以拆 Maven 模块了。')
        return

    print('按服务对：')
    for (a, b), items in sorted(edges.items(), key=lambda kv: -len(kv[1])):
        print('  %-16s → %-16s %3d 处' % (a, b, len(items)))

    print('\n被引用最多的目标包（即最该抽 Port 的地方）：')
    for pkg, n in by_target_pkg.most_common(12):
        print('  %-40s %3d 处  → %s' % (pkg, n, svc_of_pkg(pkg)))

    # ── 被「暂不归属」的包所隐藏的耦合，必须量化 ──
    # portal/dto/seed/report 被排除在服务归属之外，它们的跨服务引用不计入上面的数字。
    # **不报出来，「2 处」就会被误读成「基本可以拆了」。**
    deferred = defaultdict(Counter)
    for root in MODULE_ROOTS:
      for base, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith('.java'):
                continue
            p2 = os.path.join(base, f)
            rel = os.path.relpath(p2, root)
            my_pkg = os.path.dirname(rel).replace(os.sep, '.')
            if not my_pkg.startswith(DEFERRED):
                continue
            top = my_pkg.split('.')[0]
            for line in io.open(p2, encoding='utf-8'):
                line = line.strip()
                if not line.startswith('import ' + BASE_PKG):
                    continue
                tail = line[len('import ' + BASE_PKG):].rstrip(';')
                theirs = svc_of_pkg('.'.join(tail.split('.')[:-1]))
                if theirs:
                    deferred[top][theirs] += 1

    print('\n暂不归属的包所触及的服务（S4 拆 app-* 时才处理，此处仅量化不掩盖）：')
    for top, c in sorted(deferred.items(), key=lambda kv: -sum(kv[1].values())):
        n = sum(c.values())
        span = len(c)
        flag = '  ⚠️ 跨 %d 个服务，拆 app-* 时必须按前缀分派' % span if span > 1 else ''
        print('  %-10s %3d 处引用 → %s%s' % (top, n, dict(c), flag))

    print('\n样本（每个服务对取 2 条）：')
    for (a, b), items in sorted(edges.items(), key=lambda kv: -len(kv[1]))[:6]:
        print('  %s → %s' % (a, b))
        for rel, imp in items[:2]:
            print('     %-52s %s' % (rel, imp))


if __name__ == '__main__':
    main()
