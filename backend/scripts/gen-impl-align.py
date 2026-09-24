#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《后端实现对齐清单》—— 端点 → Controller → Service → Mapper → Entity → 库表 → 状态。

回答的问题：353 个后端端点里，每一个落在哪个控制器、走什么装配链、
是真四件套落库还是 SeedData 内存骨架；再叠加前端契约的 A/B 缺口，
得到「逐 API 实现」的工作清单。

输入：
  1. /tmp/api_contract.json、/tmp/api_align.json —— 由 api-extract.py / api-align.py 产出，
     本脚本会在缺失时自动先跑它们（同一解释器）。
  2. Java 源码 —— 装配链靠解析 final 字段注入 + implements + BaseMapper<Entity> + @TableName。

局限（避免高估）：装配链是**类级**的 —— 控制器注入了哪些 Service 是事实，
但「某个方法用了哪个 Service」不做方法体分析；SeedData 判定按**文件内引用**，
一个控制器只要还摸 SeedData 就整类记为骨架（退役是按类退，不是按方法退）。

输出：docs/api/后端实现对齐清单.md（生成文档，勿手改）。
"""
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

from _modules import ROOT, source_roots

CONTRACT_JSON = '/tmp/api_contract.json'
ALIGN_JSON = '/tmp/api_align.json'
OUT = os.path.join(ROOT, 'docs/api/后端实现对齐清单.md')
SCRIPTS = os.path.dirname(os.path.abspath(__file__))

# ---------- 输入产物 ----------

def ensure_inputs():
    if not os.path.exists(CONTRACT_JSON):
        subprocess.run([sys.executable, os.path.join(SCRIPTS, 'api-extract.py')], check=True)
    if not os.path.exists(ALIGN_JSON):
        subprocess.run([sys.executable, os.path.join(SCRIPTS, 'api-align.py')], check=True)


# ---------- Java 源码解析 ----------

FIELD_RE = re.compile(r'(?:private|protected)\s+final\s+([A-Z]\w+)(?:<[^;=]*>)?\s+\w+\s*;')
IMPLEMENTS_RE = re.compile(r'class\s+(\w+)[^{]*?\bimplements\b([^{]+)\{', re.S)
BASEMAPPER_RE = re.compile(r'interface\s+(\w+)\s+extends\s+\w*BaseMapper\s*<\s*(\w+)\s*>')
TABLENAME_RE = re.compile(r'@TableName\(\s*(?:value\s*=\s*)?"([^"]+)"')
CRUD_EXTENDS_RE = re.compile(r'class\s+(\w+)\s+extends\s+\w*CrudService\s*<\s*(\w+)')
NOT_MYBATIS = {'ObjectMapper', 'ModelMapper'}  # Jackson/MapStruct 同名尾缀，不是持久层


def scan_java():
    """一次遍历建四张索引：类→文件、类→注入依赖、接口→实现类、mapper→entity、entity→表。"""
    cls_file, cls_deps, impl_of = {}, {}, defaultdict(list)
    mapper_entity, entity_table, cls_module, crud_entity = {}, {}, {}, {}
    for root in source_roots():
        module = root.split(os.sep + 'sharehub-')[1].split(os.sep)[0]
        for base, _dirs, files in os.walk(root):
            for f in files:
                if not f.endswith('.java'):
                    continue
                path = os.path.join(base, f)
                name = f[:-5]
                src = open(path, encoding='utf-8').read()
                cls_file[name] = path
                cls_module[name] = 'sharehub-' + module
                cls_deps[name] = FIELD_RE.findall(src)
                for m in IMPLEMENTS_RE.finditer(src):
                    for iface in re.findall(r'[A-Z]\w+', m.group(2)):
                        impl_of[iface].append(m.group(1))
                # 嵌套 mapper 接口（LocMappers 之类）与顶层接口都在此覆盖
                for mm in BASEMAPPER_RE.finditer(src):
                    mapper_entity[mm.group(1)] = mm.group(2)
                tn = TABLENAME_RE.search(src)
                if tn and re.search(r'class\s+' + re.escape(name) + r'\b', src):
                    entity_table[name] = tn.group(1)
                # 单文件多实体（嵌套 entity）：逐 @TableName 往后找 class 名
                for tm in re.finditer(r'@TableName\(\s*(?:value\s*=\s*)?"([^"]+)"[^)]*\)\s*(?:public\s+)?(?:static\s+)?class\s+(\w+)', src):
                    entity_table[tm.group(2)] = tm.group(1)
                cm = CRUD_EXTENDS_RE.search(src)
                if cm:
                    crud_entity[cm.group(1)] = cm.group(2)
    return cls_file, cls_deps, impl_of, mapper_entity, entity_table, cls_module, crud_entity


def chain_of(ctrl, cls_deps, impl_of, mapper_entity, entity_table, crud_entity, seen=None):
    """控制器（或服务）的装配链：返回 (services, [(mapper, 表)])。"""
    seen = seen or set()
    services, pairs = [], []
    if ctrl in crud_entity:  # extends AbstractCrudService<Entity, VO>：mapper 经构造器进基类
        ent = crud_entity[ctrl]
        pairs.append(('CRUD', entity_table.get(ent, ent)))
    for dep in cls_deps.get(ctrl, []):
        if dep in seen or dep in NOT_MYBATIS:
            continue
        seen.add(dep)
        if dep.endswith('Mapper') or dep in mapper_entity:
            ent = mapper_entity.get(dep)
            pairs.append((dep, entity_table.get(ent, '?') if ent else '?'))
        elif dep.endswith(('Service', 'Port', 'StateMachine', 'Store')):
            services.append(dep)
            targets = impl_of.get(dep, []) or [dep]
            for t in targets:
                if t.endswith(('Remote', 'Client')):  # 远程实现不是持久层
                    continue
                _s, p = chain_of(t, cls_deps, impl_of, mapper_entity, entity_table, crud_entity, seen)
                pairs += p
    return services, pairs


# ---------- 资源分组 ----------

PREFIXES = ('/api/ops/', '/api/trade/', '/api/user/', '/api/agent/', '/api/platform/',
            '/api/auth', '/internal/', '/mp/', '/notify/', '/gw/')
TWO_SEG = {'alarms', 'cs', 'reports', 'ledger', 'marketing', 'iam'}


def resource_of(path):
    p = path
    for pre in ('/api/ops/', '/api/trade/', '/api/user/', '/api/agent/', '/api/platform/'):
        if p.startswith(pre):
            rest = p[len(pre):].split('/')
            if rest[0] in TWO_SEG and len(rest) > 1 and not rest[1].startswith('{'):
                return pre + rest[0] + '/' + rest[1], rest[0] + '/' + rest[1]
            return pre + rest[0], rest[0]
    if p.startswith('/api/auth'):
        return '/api/auth', 'auth'
    root = '/'.join(p.split('/')[:3])
    return root, root


DOMAIN_OF_PREFIX = [
    ('/api/ops/', '运营 /api/ops'),
    ('/api/trade/', '交易资金 /api/trade'),
    ('/api/user/', '用户营销 /api/user'),
    ('/api/agent/', '代理商 /api/agent'),
    ('/api/platform/', '平台配置 /api/platform'),
    ('/api/auth', '认证 /api/auth'),
    ('/internal/', '内部调用 /internal'),
    ('/mp/', 'C端 /mp'),
]


def domain_of(path):
    for pre, name in DOMAIN_OF_PREFIX:
        if path.startswith(pre):
            return name
    return '其他'


# ---------- 主流程 ----------

def main():
    ensure_inputs()
    contract = json.load(open(CONTRACT_JSON))
    align = json.load(open(ALIGN_JSON))
    eps = contract['endpoints']

    cls_file, cls_deps, impl_of, mapper_entity, entity_table, cls_module, crud_entity = scan_java()

    # 判「骨架」看**注入**（final 字段），不能只看文本出现 —— javadoc 提一句 SeedData 就会误伤
    seed_ctrls = {c for c in cls_file
                  if c.endswith('Controller') and 'SeedData' in cls_deps.get(c, [])}
    not_called = {(e['verb'], e['path']) for e in align['notCalledByFrontend']}
    mismatch_by_path = defaultdict(list)
    for m in align['shapeMismatch']:
        verb, _, mpath = m['endpoint'].partition(' ')
        mismatch_by_path[mpath].append(m)

    rows = []
    for e in eps:
        ctrl = e['handler'].split('#')[0]
        services, pairs = chain_of(ctrl, cls_deps, impl_of, mapper_entity, entity_table, crud_entity)
        if ctrl in seed_ctrls:
            status = '🟡骨架'
        elif pairs:
            status = '✅'
        else:
            status = '◐无表'  # 聚合/转发/token 等非持久端点，人工复核
        marks = []
        if (e['verb'], e['path']) in not_called:
            marks.append('C')
        if e['path'] in mismatch_by_path and e['verb'] == 'GET':
            marks.append('B')
        if not e.get('perm') and e['path'].startswith('/api/') and not e['path'].startswith('/api/auth'):
            marks.append('P')
        rows.append({
            'verb': e['verb'], 'path': e['path'],
            'perm': ' 或 '.join(e.get('perms') or ([e['perm']] if e.get('perm') else [])),
            'handler': e['handler'], 'module': cls_module.get(ctrl, '?'),
            'services': services, 'pairs': sorted(set(pairs)),
            'status': status, 'marks': marks,
        })

    # ---- 汇总 ----
    n = defaultdict(int)
    for r in rows:
        n[r['status']] += 1
        for m in r['marks']:
            n['mark' + m] += 1
    missing = align['missingInBackend']

    out = []
    w = out.append
    w('# 后端实现对齐清单（端点 → Controller → Service → Mapper → 库表）\n')
    w('> **本文件由脚本生成，不要手改** —— `python3 backend/scripts/gen-impl-align.py`'
      '（会自动先跑 api-extract / api-align）。\n>')
    w('> 逐端点回答「这个 API 现在实现到哪一层」。装配链为**类级**静态解析（final 字段注入），')
    w('> SeedData 判定按控制器文件内引用 —— 只要还摸 SeedData 整类记骨架。\n')
    w('## 一、总量\n')
    w('| 口径 | 数 |')
    w('|---|---:|')
    w(f'| 后端已有端点 | {len(rows)} |')
    w(f'| ├ ✅ 走 Mapper 落库 | {n["✅"]} |')
    w(f'| ├ 🟡 SeedData 骨架（{len(seed_ctrls)} 个控制器待退役） | {n["🟡骨架"]} |')
    w(f'| └ ◐ 无持久层（聚合/转发/认证等，逐条人工复核） | {n["◐无表"]} |')
    w(f'| ⬜ 前端在调、后端缺失（A 类） | {len(missing)} |')
    w(f'| ⚠️ B 类字段错位（挂在既有 GET 上） | {len(align["shapeMismatch"])} |')
    w(f'| C 类后端有前端没调（待裁决：接线 or 废弃） | {len(align["notCalledByFrontend"])} |')
    w(f'| P 类 /api 端点缺权限码 | {n["markP"]} |')
    w('')
    w('骨架控制器：' + ' · '.join(f'`{c}`' for c in sorted(seed_ctrls)) + '\n')

    w('## 二、逐端点清单\n')
    w('> 状态列：✅ 已走 Mapper 落库 · 🟡 SeedData 骨架 · ◐ 无持久层（读模型/转发/认证）。')
    w('> 标记列：**B** 字段错位（明细见[前后端对齐缺口](./前后端对齐缺口.md)）· **C** 前端未调用 · **P** 缺权限码。\n')

    bydomain = defaultdict(lambda: defaultdict(list))
    for r in rows:
        bydomain[domain_of(r['path'])][resource_of(r['path'])[1]].append(r)

    for dom, _name in [(d, d) for _, d in DOMAIN_OF_PREFIX] + [('其他', '其他')]:
        if dom not in bydomain:
            continue
        total = sum(len(v) for v in bydomain[dom].values())
        w(f'### {dom}（{total} 端点）\n')
        w('| 端点 | 权限码 | Handler | Service | Mapper→表 | 状态 | 标记 |')
        w('|---|---|---|---|---|:---:|---|')
        for res in sorted(bydomain[dom]):
            for r in sorted(bydomain[dom][res], key=lambda x: (x['path'], x['verb'])):
                svc = '<br>'.join(r['services'][:3]) or '—'
                mt = '<br>'.join(f'{m}→`{t}`' for m, t in r['pairs'][:4]) or '—'
                w(f"| `{r['verb']} {r['path']}` | `{r['perm']}` | {r['handler']} "
                  f"| {svc} | {mt} | {r['status']} | {' '.join(r['marks'])} |")
        w('')

    w('## 三、⬜ A 类缺失端点（前端在调、后端没有，逐个补）\n')
    w('| 前端调用处 | 端点 |')
    w('|---|---|')
    for m in missing:
        w(f"| `{m['file']}` | `{m['verb']} {m['path']}` |")
    w('')
    w('## 四、装配链附注\n')
    w('- Service 列取控制器注入的前 3 个；Mapper→表 列取链上前 4 个（完整链跑本脚本看 JSON）。')
    w('- ◐ 无持久层 ≠ 未实现：报表读模型、网关转发、认证端点天然无表；但骨架控制器之外仍为 ◐ 的写端点要人工确认。')
    open(OUT, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print(f'✅ {len(rows)} 端点 → {OUT}')
    print(f'   ✅落库 {n["✅"]} · 🟡骨架 {n["🟡骨架"]} · ◐无表 {n["◐无表"]} · A缺失 {len(missing)} · P缺权限码 {n["markP"]}')

    # ── 棘轮卡口：骨架 / A缺失 / P缺码 三数只降不升 ──────────────────────
    # 基线文件随仓库提交；数字下降后**必须**同步改基线（在 review 里显形）。
    # 反向对照：把基线某数改小重跑 --check 应当失败（「永远绿的卡口比没有卡口更危险」）。
    counts = {'skeleton': n['🟡骨架'], 'missing': len(missing), 'noPerm': n['markP']}
    if os.path.exists(BASELINE):
        base = json.load(open(BASELINE))
        worse = {k: (base.get(k, 0), v) for k, v in counts.items() if v > base.get(k, 0)}
        if worse:
            for k, (b, v) in worse.items():
                print(f'❌ 棘轮回退: {k} 基线 {b} → 现值 {v}（只许降不许升）')
            sys.exit(1)
        better = {k: v for k, v in counts.items() if v < base.get(k, 0)}
        if better:
            print(f'ℹ️ 计数已下降 {better}，请同步更新基线: {BASELINE}')
    else:
        json.dump(counts, open(BASELINE, 'w'), indent=2)
        print(f'📌 首次运行，基线已写入 {BASELINE}')


BASELINE = os.path.join(SCRIPTS, 'impl-align-baseline.json')

if __name__ == '__main__':
    main()
