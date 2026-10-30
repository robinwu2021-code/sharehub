#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能评审汇总：把 docs/technical/功能评审/S*.md 的任务清单表汇成 README.md。

为什么要脚本：各阶段文档会继续被修订（补任务、调优先级），手抄的汇总表第二天就过期。
README 只由本脚本生成，改任务改阶段文档，再重跑。

真值来源：
  · 任务：各阶段 §三 任务表中以 `| OPT-S<n>-<序号> |` 开头、7 列的行
  · 功能 → 目标包：docs/requirements/服务端功能清单.md 元数据行（与 review-evidence.py 同一正则）
  · 目标包 → Maven 模块：docs/technical/服务端包名规划.md §一（下方 MODULE 表手工对齐，变更时同步）

用法：python3 backend/scripts/review-summary.py     # 写 docs/technical/功能评审/README.md
任何任务行不是 7 列、或优先级无法识别，脚本非零退出 —— 宁可失败也不静默漏行。
"""
import collections
import glob
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DIR = os.path.join(ROOT, 'docs/technical/功能评审')
FEATURES = os.path.join(ROOT, 'docs/requirements/服务端功能清单.md')
OUT = os.path.join(DIR, 'README.md')

META = re.compile(r'^#### (SF-[A-Z]+-\d+) (.+)$\n> 包 `([^`]+)`', re.M)
ROW = re.compile(r'^\| (OPT-S(\d+)-(\d+)) \|')
OPT = re.compile(r'OPT-S\d+-\d+')
DECISION = re.compile(r'(?<![A-Za-z0-9-])(K\d{1,2}|C-S\d+-\d+|C\d{2})(?![\d-])')

# 业务模块 → Maven 模块（服务端包名规划 §一）
MODULE = {
    'operator': 'sharehub-svc-platform', 'network': 'sharehub-svc-platform', 'system': 'sharehub-svc-platform',
    'device': 'sharehub-svc-core', 'sales': 'sharehub-svc-core', 'consumer': 'sharehub-svc-core',
    'ops': 'sharehub-svc-ops',
    'finance': 'sharehub-svc-finance', 'settlement': 'sharehub-svc-finance', 'invoice': 'sharehub-svc-finance',
    'report': 'sharehub-svc-report 🆕',
    'auth': 'sharehub-auth 🆕',
    'gateway': 'sharehub-svc-gateway',
    'common': 'sharehub-common', 'integration': 'sharehub-app',
}
MODULE_ORDER = ['sharehub-svc-platform', 'sharehub-svc-core', 'sharehub-svc-ops', 'sharehub-svc-finance',
                'sharehub-svc-report 🆕', 'sharehub-auth 🆕', 'sharehub-svc-gateway', 'sharehub-common',
                'sharehub-app', '跨模块']
DAYS = {'S': 0.5, 'M': 2.0, 'L': 5.0}   # 规模折算人天：S ≤0.5 取 0.5；M 1–3 取 2；L >3 取 5（下限估计）


def features():
    s = io.open(FEATURES, encoding='utf-8').read()
    return {m.group(1): (m.group(2), m.group(3)) for m in META.finditer(s)}


def cells(line):
    return [c.strip() for c in re.split(r'(?<!\\)\|', line.strip())[1:-1]]


def title(content):
    # 代码段里的 * 会把粗体切断（如 `/internal/*`），先换成全角星号
    content = re.sub(r'`([^`]*)`', lambda m: m.group(1).replace('*', '＊'), content).strip()
    # 只认开头的粗体作标题；粗体在句中时它是强调，不是标题
    m = re.match(r'\*\*(.+?)\*\*', content)
    t = m.group(1) if m else re.split(r'[：；。]', content.replace('**', ''))[0]
    t = re.sub(r'`', '', t).strip('：: ')
    t = t.replace('＊', '\\*')   # 还原代码段里的星号并转义，免得表格里被当成粗体
    return t if len(t) <= 60 else t[:58] + '…'


def norm_priority(raw):
    p = raw.replace('*', '').strip()
    m = re.match(r'(P[012])(?:（(.+?)）?)?$', p) or re.match(r'(P[012])(?:（(.+))?', p)
    if not m:
        return None, None
    return m.group(1), (m.group(2) or '').rstrip('）') or None


def load():
    F = features()
    stages, tasks, errors = collections.OrderedDict(), [], []
    files = sorted(glob.glob(os.path.join(DIR, 'S*.md')),
                   key=lambda f: int(re.match(r'S(\d+)', os.path.basename(f)).group(1)))
    for f in files:
        base = os.path.basename(f)
        sid = re.match(r'(S\d+)', base).group(1)
        stages[sid] = base
        for no, line in enumerate(io.open(f, encoding='utf-8'), 1):
            if not ROW.match(line):
                continue
            c = cells(line)
            if len(c) != 7:
                errors.append('%s:%d 列数 %d ≠ 7' % (base, no, len(c)))
                continue
            oid, func, dims, content, pri, size, dep = c
            p, cond = norm_priority(pri)
            if not p:
                errors.append('%s:%d 优先级无法识别：%s' % (base, no, pri))
                continue
            code = re.match(r'([A-Z]+-\d+)', func)
            fid = 'SF-' + code.group(1) if code else None
            pkg = F.get(fid, (None, None))[1] if fid else None
            mod = MODULE.get(pkg.split('.')[0], '跨模块') if pkg else '跨模块'
            sz = re.match(r'([SML])', size)
            tasks.append(dict(id=oid, stage=sid, file=base, func=func, fid=fid, pkg=pkg, mod=mod, dims=dims,
                              title=title(content), p=p, cond=cond, size=sz.group(1) if sz else '?',
                              deps=sorted(set(OPT.findall(dep)) - {oid}), dep=dep,
                              decisions=sorted(set(DECISION.findall(dep + ' ' + pri)))))
    return stages, tasks, errors


def key(t):
    s, n = re.match(r'OPT-S(\d+)-(\d+)', t['id']).groups()
    return int(s), int(n)


def main():
    stages, T, errors = load()
    if errors:
        sys.exit('任务行解析失败：\n  ' + '\n  '.join(errors))
    ids = {t['id'] for t in T}
    dangling = sorted({d for t in T for d in t['deps'] if d not in ids})

    L = ['# 服务端功能评审 · 任务汇总', '',
         '> **本文件由脚本生成，勿手改** —— `python3 backend/scripts/review-summary.py`。'
         '改任务请改各阶段文档 §三，再重跑。', '>',
         '> 规划与方法见 [00-任务规划](00-任务规划.md)。优先级：**P0** 缺陷或阻塞第一阶段 · **P1** 第一阶段需要、可后置 · **P2** 质量提升。'
         '括号内为前提（如「K7 前提」= 运营方裁决 K7 后才成立或才需要做；「上线闸门」= 上线前必须完成）。', '']

    # 一、阶段索引
    cnt = collections.defaultdict(collections.Counter)
    for t in T:
        cnt[t['stage']][t['p']] += 1
        if t['p'] == 'P0' and t['cond']:
            cnt[t['stage']]['P0c'] += 1
    total = collections.Counter(t['p'] for t in T)
    L += ['## 一、阶段索引', '',
          '| 阶段 | 评审文档 | 任务 | P0 | 其中带前提或闸门 | P1 | P2 |', '|---|---|---:|---:|---:|---:|---:|']
    for sid, base in stages.items():
        c = cnt[sid]
        L.append('| %s | [%s](%s) | %d | %d | %s | %d | %d |' % (
            sid, base[:-3], base, c['P0'] + c['P1'] + c['P2'], c['P0'], c['P0c'] or '—', c['P1'], c['P2']))
    L.append('| **合计** | | **%d** | **%d** | %d | **%d** | **%d** |' % (
        len(T), total['P0'], sum(cnt[s]['P0c'] for s in stages), total['P1'], total['P2']))
    L.append('')

    # 二、P0
    p0 = sorted([t for t in T if t['p'] == 'P0'], key=key)
    hard = [t for t in p0 if not t['cond']]
    gate = [t for t in T if t['cond'] and '上线闸门' in t['cond']]
    condp0 = [t for t in p0 if t['cond'] and '上线闸门' not in t['cond']]
    L += ['## 二、P0 清单', '',
          '### 2.1 无前提的 P0（%d）' % len(hard), '',
          '| 编号 | 模块 | 功能 | 内容 | 规模 | 依赖 |', '|---|---|---|---|---|---|']
    for t in hard:
        L.append('| [%s](%s) | %s | %s | %s | %s | %s |' % (t['id'], t['file'], t['mod'], t['func'], t['title'],
                                                         t['size'], ' · '.join(t['deps']) or '—'))
    L += ['', '### 2.2 上线闸门（%d）' % len(gate), '',
          '| 编号 | 优先级 | 模块 | 内容 | 规模 |', '|---|---|---|---|---|']
    for t in sorted(gate, key=key):
        L.append('| [%s](%s) | %s | %s | %s | %s |' % (t['id'], t['file'], t['p'], t['mod'], t['title'], t['size']))
    L += ['', '### 2.3 带前提的 P0（%d）' % len(condp0), '',
          '前提不成立时按 P1 处理；裁决出来前先不排期。', '',
          '| 前提 | 任务 |', '|---|---|']
    byc = collections.OrderedDict()
    for t in condp0:
        byc.setdefault(t['cond'], []).append(t)
    for c, ts in sorted(byc.items(), key=lambda x: -len(x[1])):
        L.append('| %s | %s |' % (c, ' · '.join('[%s](%s) %s' % (t['id'], t['file'], t['title']) for t in ts)))
    L.append('')

    # 三、按 Maven 模块的工作量
    L += ['## 三、按 Maven 模块的工作量', '',
          '人天按规模下限折算（S = 0.5、M = 2、L = 5），只用于排期比例，不是承诺。'
          '「跨模块」= 任务功能列为「跨功能」或多个功能。模块按[服务端包名规划](../服务端包名规划.md)的目标模块，🆕 为待新建。', '',
          '| 模块 | P0 | P1 | P2 | 合计 | 约人天 | 其中 P0 人天 |', '|---|---:|---:|---:|---:|---:|---:|']
    for m in MODULE_ORDER:
        ts = [t for t in T if t['mod'] == m]
        if not ts:
            continue
        c = collections.Counter(t['p'] for t in ts)
        d = sum(DAYS.get(t['size'], 0) for t in ts)
        d0 = sum(DAYS.get(t['size'], 0) for t in ts if t['p'] == 'P0')
        L.append('| `%s` | %d | %d | %d | %d | %.0f | %.0f |' % (m, c['P0'], c['P1'], c['P2'], len(ts), d, d0))
    d = sum(DAYS.get(t['size'], 0) for t in T)
    d0 = sum(DAYS.get(t['size'], 0) for t in T if t['p'] == 'P0')
    L += ['| **合计** | %d | %d | %d | %d | **%.0f** | **%.0f** |' % (total['P0'], total['P1'], total['P2'], len(T), d, d0), '']

    # 四、依赖关系
    fan = collections.Counter(d for t in T for d in t['deps'])
    byid = {t['id']: t for t in T}
    blocked_p0 = [t for t in p0 if any(byid.get(d, {}).get('p') in ('P1', 'P2') for d in t['deps'])]
    L += ['## 四、依赖关系', '',
          '### 4.1 被依赖最多的任务（先做）', '',
          '| 任务 | 优先级 | 被依赖次数 | 内容 |', '|---|---|---:|---|']
    for oid, n in fan.most_common(20):
        t = byid.get(oid)
        if t:
            L.append('| [%s](%s) | %s | %d | %s |' % (oid, t['file'], t['p'], n, t['title']))
    L += ['', '### 4.2 依赖了非 P0 任务的 P0（排期时须一并提前）', '']
    if blocked_p0:
        L += ['| P0 任务 | 依赖的非 P0 任务 |', '|---|---|']
        for t in blocked_p0:
            L.append('| [%s](%s) | %s |' % (t['id'], t['file'], ' · '.join(
                '%s（%s）' % (d, byid[d]['p']) for d in t['deps'] if byid.get(d, {}).get('p') in ('P1', 'P2'))))
    else:
        L.append('无。')
    xr = [t for t in T if re.search(r'共用|ai-shop|跨仓|网关|支付服务|调度服务', t['dep'])]
    L += ['', '### 4.3 跨仓依赖（共用支付 / 调度 / 网关）（%d）' % len(xr), '',
          '| 任务 | 优先级 | 依赖 |', '|---|---|---|']
    for t in sorted(xr, key=key):
        L.append('| [%s](%s) | %s | %s |' % (t['id'], t['file'], t['p'], t['dep'].replace('|', '\\|')))
    if dangling:
        L += ['', '> ⚠️ 依赖列引用了不存在的任务编号：%s' % ' · '.join(dangling)]
    L.append('')

    # 五、需运营方裁决
    dec = collections.defaultdict(list)
    for t in T:
        for k in t['decisions']:
            dec[k].append(t)
    L += ['## 五、需运营方裁决', '',
          '下表只统计**任务的依赖或优先级列**里点名的裁决编号，即「不裁决就排不了」的那部分；'
          '各阶段 §五（S7 为 §2.7）另有不卡任务的裁决项与选项代价，裁决时以阶段文档为准。', '',
          '| 裁决 | 卡住的任务数 | 其中 P0 | 任务 |', '|---|---:|---:|---|']
    for k, ts in sorted(dec.items(), key=lambda x: (-len(x[1]), x[0])):
        ts = sorted(ts, key=key)
        L.append('| %s | %d | %d | %s |' % (k, len(ts), sum(t['p'] == 'P0' for t in ts),
                                          ' · '.join('[%s](%s)' % (t['id'], t['file']) for t in ts)))
    L += ['', '各阶段裁决节：' + ' · '.join('[%s](%s)' % (s, b) for s, b in stages.items()), '']

    # 六、全部任务
    L += ['## 六、全部任务（按优先级、阶段排序）', '',
          '| 编号 | 优先级 | 规模 | 模块 | 功能 | 维度 | 内容 |', '|---|---|---|---|---|---|---|']
    for t in sorted(T, key=lambda t: (t['p'], bool(t['cond']), key(t))):
        pr = t['p'] + ('（%s）' % t['cond'] if t['cond'] else '')
        L.append('| [%s](%s) | %s | %s | %s | %s | %s | %s |' % (t['id'], t['file'], pr, t['size'], t['mod'],
                                                              t['func'], t['dims'], t['title']))
    L.append('')

    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('✓ %s：%d 阶段 · %d 任务（P0 %d / P1 %d / P2 %d）' % (
        os.path.relpath(OUT, ROOT), len(stages), len(T), total['P0'], total['P1'], total['P2']))


if __name__ == '__main__':
    main()
