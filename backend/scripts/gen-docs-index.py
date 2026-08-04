#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成文档总索引 docs/README.md。

为什么需要：仓库有 120+ 份文档、**零入口**，且存在同题多份（4 份权限文档、5 份架构文档），
新人（和三天后的自己）无法判断「哪份是权威、哪份已被取代」。
文档不是不在本地，是**没有秩序**。

分工与本仓其它生成器一致 —— **事实生成，判断手写**：
  · 标题 / 状态行 / 最后改动时间 / 是否脚本生成 —— 从文件与 git 里读，自动更新；
  · 权威关系（谁取代谁、哪份是 SSOT）—— 写在下面的 AUTHORITY 表里，机器推不出来。

用法：python3 backend/scripts/gen-docs-index.py
"""
import io
import os
import re
import subprocess
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, 'docs')
OUT = os.path.join(DOCS, 'README.md')

# ── 手写部分一：分组与导读 ──
GROUPS = OrderedDict([
    ('start', ('从这里开始', '')),
    ('arch', ('架构（现行）', '**当前有效的架构结论**。有冲突时以本组为准。')),
    ('adr', ('决策记录 ADR', '单条决策的取舍过程与被否决的方案。ADR 一经作出不删改，'
                         '被取代时在头部标注去向。')),
    ('gen', ('纯生成文档（勿手改）', '由脚本从代码/库里抽取，**不可能与实现不一致**。'
                              '改了会在下次重跑时被覆盖。带 ⚙️ 的文档同样是生成物，'
                              '但因为是某个领域的权威，列在上面的组里。')),
    ('req', ('需求与功能', '做什么。')),
    ('tdd', ('技术方案 TDD', '怎么做。多为单模块的实现细节。')),
    ('legacy', ('已被取代 ⚠️', '**不要据此实施**。保留是为了追溯当时的判断，去向见备注。')),
    ('other', ('其它', '')),
])

# ── 手写部分二：权威关系。机器读不出「谁取代谁」，只能人写 ──
AUTHORITY = {
    'technical/架构设计总纲-多设备共享平台.md': (
        'arch', '★ **架构总入口**', '汇总 ADR-017/018 + 模块分层，五项拍板的完整方案'),
    'technical/未完成清单.md': (
        'start', '★ **未完成清单**', '按「可立刻做」/「需外部输入」分组 + 本轮沉淀的经验'),
    'technical/设计v3-数据库变更.md': (
        'arch', '★ v3 库表变更', '新增 12 表 / 改造 6 表，含迁移与校验顺序'),
    'api/设计v3-接口清单.md': (
        'arch', '★ v3 接口清单', '新增约 47 端点（含北向 /ocpi 新前缀）+ 改造 14 处'),
    'technical/设计v3-工程骨架.md': (
        'arch', '★ v3 工程骨架', '16 个 Maven 模块 · 包结构 · S1–S4 迁移路径'),
    'technical/业务模块分层.md': (
        'arch', '★ 模块边界 SSOT', '32 模块 6 层，脚本校验（表归位/循环/反向依赖）'),
    'technical/ADR/ADR-017-单体与微服务双形态部署.md': (
        'adr', '现行', '双形态机制有效；服务数经 ADR-018 调整为 5'),
    'technical/ADR/ADR-018-多设备类型与多供应商的抽象边界.md': (
        'adr', '现行', '多设备抽象 + 服务数 5 + 供应商红线'),
    'technical/ADR/ADR-019-会话存储归属与划拨最终一致.md': (
        'adr', '**待放行**', '解决 G2 两处跨服务事务：令牌是分类问题（已做）· 划拨改最终一致（待）'),
    'technical/ADR/ADR-016-目标架构为微服务与服务边界.md': (
        'legacy', '被 ADR-017 取代', '10 服务划分作废；接口契约写法仍可参考'),
    'technical/架构-微服务边界与接口.md': (
        'legacy', '被架构设计总纲取代', '基于 ADR-016 的 10 服务，边界已重划为 5'),
    'technical/架构-业务模块微服务化与前端对应.md': (
        'legacy', '被架构设计总纲取代', '同上，10 服务前提已作废'),
    'technical/实现状态总表.md': (
        'start', '★ **实现进度唯一权威**', '开发前必读、完成后必更（变更日志在文末）'),
    'technical/db-design.md': (
        'arch', '★ 库表设计', '**为什么这么设计**；现状事实看 db-schema-reference'),
    'api/README.md': (
        'arch', '★ 接口设计', '**为什么这么划**；逐端点 I/O 看 api/reference'),
    'technical/运营端权限方案.md': (
        'arch', '★ 权限落地权威', '与后端代码逐一对齐、已实测'),
    'technical/权限体系设计.md': ('other', '总体设计', 'as-built 与设计态混合，逐段标注'),
    'technical/权限管理方案.md': ('legacy', '草稿，被运营端权限方案取代', '未确认即被实现覆盖'),
    'requirements/运营端功能清单.md': ('start', '★ 运营端需求 SSOT', '98 菜单叶'),
    'requirements/C端功能清单.md': ('req', '★ C端需求 SSOT', '17 模块，编号 C-XX-NN'),
    'requirements/功能权限清单.md': ('req', '★ RBAC SSOT', '权限码 = 资源边界'),
    'technical/diagrams/README.md': ('arch', '架构图（PPT 用）', '10 张 1600×900 矢量图'),
}

# 生成物：由脚本产出，手改会被覆盖
GENERATED = {
    'api/reference.md': 'api-extract.py + gen-api-doc.py',
    'api/contract.json': 'api-extract.py（端点真值，两个消费者共读）',
    'api/前后端对齐缺口.md': 'api-align.py',
    'technical/db-schema-reference.md': 'gen-db-doc.py',
    'technical/业务模块分层.md': 'module-graph.py',
    'README.md': 'gen-docs-index.py（本文件）',
}

SKIP_DIRS = {'raw'}


def git_date(rel):
    r = subprocess.run(['git', 'log', '-1', '--format=%ad', '--date=short', '--', rel],
                       cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip() or '—'


def read_head(path):
    """取标题与状态行。"""
    try:
        head = io.open(path, encoding='utf-8').read(2000)
    except Exception:
        return '', ''
    title = ''
    m = re.search(r'^#\s+(.+)$', head, re.M)
    if m:
        title = m.group(1).strip()
    status = ''
    m = re.search(r'^>\s*状态[：:]\s*(.+)$', head, re.M)
    if m:
        status = m.group(1).strip()
    else:
        m = re.search(r'^状态[：:]\s*(.+)$', head, re.M)
        if m:
            status = m.group(1).strip()
    status = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', status)
    status = re.sub(r'\*\*|`', '', status)
    return title, status[:80]


def classify(rel):
    # AUTHORITY 优先：一份文档可以既是生成物又是某领域权威（如 业务模块分层.md），
    # 这时它该出现在「架构」组里而不是被降级到「生成文档」组，是否生成用 ⚙️ 标记表达。
    if rel in AUTHORITY:
        return AUTHORITY[rel][0]
    if rel in GENERATED:
        return 'gen'
    if rel.startswith('technical/ADR/'):
        return 'adr'
    if rel.startswith('requirements/'):
        return 'req'
    if '/TDD-' in rel or rel.startswith('technical/TDD-'):
        return 'tdd'
    if rel.startswith('competitor/'):
        return 'other'
    return 'other'


def main():
    docs = []
    for base, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in sorted(files):
            if not f.endswith('.md') and f != 'contract.json':
                continue
            rel = os.path.relpath(os.path.join(base, f), DOCS)
            if rel == 'README.md':
                continue
            title, status = read_head(os.path.join(base, f))
            docs.append({'rel': rel, 'title': title or f, 'status': status,
                         'group': classify(rel), 'date': git_date('docs/' + rel),
                         'gen': GENERATED.get(rel)})

    by = OrderedDict((g, []) for g in GROUPS)
    for d in docs:
        by[d['group']].append(d)

    L = ['# 文档索引\n',
         '> **本文件由脚本生成** —— `python3 backend/scripts/gen-docs-index.py`。\n>',
         '> 分组与「谁取代谁」的判断写在脚本的 `AUTHORITY` 表里（机器推不出权威关系）；'
         '标题、状态、改动日期从文件与 git 里读，自动更新。\n']

    L.append('| | |\n|---|---|')
    L.append('| 文档总数 | %d |' % len(docs))
    L.append('| 其中脚本生成 | %d（**勿手改**） |' % len(by['gen']))
    L.append('| 已被取代 | %d（**勿据此实施**） |' % len(by['legacy']))
    L.append('')
    L.append('**冲突时的判定顺序**：生成文档（代码真值）> 现行架构 > ADR > TDD > 需求。'
             '若发现文档与代码不符，以代码为准并回头修文档 —— 这条在 [api/README](./api/README.md) §十 有先例。\n')

    for g, (name, desc) in GROUPS.items():
        items = by[g]
        if g == 'start':
            L.append('\n## 从这里开始\n')
            L.append('按顺序读这四份，能建立完整心智：\n')
            L.append('1. [架构设计总纲](./technical/架构设计总纲-多设备共享平台.md) —— '
                     '**架构总入口**：业务抽象 → 模块 → 服务边界 → 部署 → 路线；')
            L.append('2. [业务模块分层](./technical/业务模块分层.md) ⚙️ —— '
                     '32 模块 6 层，边界由脚本校验，不是画出来的；')
            L.append('3. [实现状态总表](./technical/实现状态总表.md) —— '
                     '**哪些真做完了**。开发前必读、完成后必更；')
            L.append('4. [未完成清单](./technical/未完成清单.md) —— '
                     '**还差什么**：可立刻做的 10 项、需外部输入的 6 项，以及本轮沉淀的经验；')
            L.append('5. [运营端功能清单](./requirements/运营端功能清单.md) —— '
                     '要做什么（98 菜单叶）。\n')
            continue
        if not items:
            continue
        L.append('\n## %s（%d）\n' % (name, len(items)))
        if desc:
            L.append('%s\n' % desc)
        if g == 'gen':
            L.append('| 文档 | 由谁生成 | 更新 |\n|---|---|---|')
            for d in sorted(items, key=lambda x: x['rel']):
                L.append('| [%s](./%s) | `%s` | %s |'
                         % (d['title'], d['rel'], d['gen'] or '—', d['date']))
        elif g == 'legacy':
            L.append('| 文档 | 去向 | 备注 |\n|---|---|---|')
            for d in sorted(items, key=lambda x: x['rel']):
                a = AUTHORITY.get(d['rel'], ('', '—', ''))
                L.append('| ~~[%s](./%s)~~ | **%s** | %s |' % (d['title'], d['rel'], a[1], a[2]))
        else:
            L.append('| 文档 | 定位 | 状态 | 更新 |\n|---|---|---|---|')
            key = (lambda x: (0 if x['rel'] in AUTHORITY else 1, x['rel']))
            for d in sorted(items, key=key):
                a = AUTHORITY.get(d['rel'])
                role = ('%s —— %s' % (a[1], a[2])) if a else '—'
                mark = ' ⚙️' if d['gen'] else ''
                L.append('| [%s](./%s)%s | %s | %s | %s |'
                         % (d['title'], d['rel'], mark, role, d['status'] or '—', d['date']))
        L.append('')

    L.append('\n---\n')
    L.append('## 重跑全部生成物\n')
    L.append('```bash\npython3 backend/scripts/api-extract.py     # 端点真值 → docs/api/contract.json\n'
             'python3 backend/scripts/gen-api-doc.py     # → docs/api/reference.md\n'
             'python3 backend/scripts/api-align.py       # → docs/api/前后端对齐缺口.md\n'
             'python3 backend/scripts/gen-db-doc.py      # → docs/technical/db-schema-reference.md\n'
             'python3 backend/scripts/module-graph.py    # → docs/technical/业务模块分层.md\n'
             'python3 backend/scripts/arch-guard.py      # 架构纪律三卡口（--strict 进 CI）\n'
             'python3 backend/scripts/gen-docs-index.py  # → docs/README.md（本文件）\n'
             'cd docs/technical/diagrams && python3 build.py   # → 10 张架构图\n```\n')

    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('写出 docs/README.md')
    for g, (name, _) in GROUPS.items():
        if by[g]:
            print('  %-14s %d' % (name, len(by[g])))


if __name__ == '__main__':
    main()
