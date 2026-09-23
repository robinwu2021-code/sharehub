#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""架构纪律卡口（ADR-017 四条纪律的前三条的自动校验）。

  G1 跨模块 JOIN      —— 手写 SQL 里 join 了别的服务的表（白名单：报表读模型）
  G2 跨服务事务       —— 一个 @Transactional 里写了多个服务的表。**跟调用链**，不只看方法体
  G3 跨服务注入       —— @Autowired/构造注入了别的服务的实现类（应经 api 层的 Port 接口）

**没有卡口的纪律等于没有纪律** —— 这是 ADR-017 的原话，本脚本是它的兑现。

G2 为什么必须跟调用链：早先只扫方法体的版本报「0 处跨界事务」，
而实际存在 `AgentAssignmentServiceImpl.assign` 经 `AgentOwnershipSync.apply()`
在同一事务里写 platform 与 core 两侧的表 —— 那一处是人工找到的。
**不跟调用链的检测给出的是虚假的安全感，比不检测更危险。**

服务划分直接 import `module-graph.py`，不复制一份 —— 同一个问题两套口径必然给出两个答案
（本项目实测过：ops-web 的 parity 脚本自己数出 267，后端抽取器数出 268）。

**已知局限**（写在这里，免得把「通过」误当成「安全」）：
  · 调用链深度上限 6 层，超深的间接跨界抓不到；
  · 只跟「字段.方法()」与同类方法调用 —— 经 Spring 事件、反射、动态代理的跨界抓不到；
  · G3 的服务归属靠「类触及哪些实体」反推（Mapper 走泛型参数）。
    **不直接碰表的纯编排类推不出归属，会被跳过**；拆成 svc-* 后改为按 Maven 模块判定才准确。
  · 三个卡口都做过反向对照（注入违规能被抓到），但反向对照只证明「能抓到这一类」，
    不证明「抓得全」。

用法：
    python3 backend/scripts/arch-guard.py            # 报告
    python3 backend/scripts/arch-guard.py --strict   # 有违规则非零退出（CI 卡口）
"""
import io
import os
import re
import sys
import importlib.util
from collections import OrderedDict, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# 扫描根须覆盖全部 svc 模块：抽出去的代码若不扫，跨界问题会「消失」。
# 本会话已四次遇到「报绿但没扫」，每次动模块结构都要回头查这里。
SRC = os.path.join(ROOT, 'backend/sharehub-app/src/main/java')
EXTRA_SRC = [os.path.join(ROOT, 'backend', m, 'src/main/java')
             for m in ('sharehub-svc-gateway', 'sharehub-svc-finance', 'sharehub-svc-platform', 'sharehub-svc-ops', 'sharehub-svc-core', 'sharehub-api')]
EXTRA_SRC = [p for p in EXTRA_SRC if os.path.isdir(p)]
# 2026-09-23：EXTRA_SRC 此前**算出来却从没被用过** —— 三处 os.walk 全是 SRC 一个目录，
# 只扫 sharehub-app（62 个文件），业务代码所在的 5 个 svc 模块（约 567 个）一个没扫，
# 于是四个卡口长期报「违规 0」。ALL_SRC 是唯一的扫描入口，新增模块只改这里。
ALL_SRC = [SRC] + EXTRA_SRC


def walk_java():
    """遍历全部扫描根下的 .java，产出 (绝对路径, 相对模块根的短路径)。

    短路径统一去掉 `ai/neargo/sharehub/` 前缀，使白名单与台账里的条目
    在哪个模块都写成同一种形状（如 `report/mapper/ReportMappers.java`）。
    """
    for root in ALL_SRC:
        for base, _, fs in os.walk(root):
            for f in fs:
                if not f.endswith('.java'):
                    continue
                p = os.path.join(base, f)
                yield p, os.path.relpath(p, root).replace('ai/neargo/sharehub/', '')

# ── 白名单。每条都要写清「为什么可以例外」，否则白名单会变成垃圾桶 ──
JOIN_WHITELIST = {
    # 报表是跨域读模型，单体期直接 JOIN；拆分时改为订阅事件落宽表（总纲 §5.2）。
    'report/mapper/ReportMappers.java',
}

# ── G3 的两类豁免（2026-09-23 修好扫描面后新增；每条都写清「为什么可以例外」）──
#
# 1) app 层的**跨域只读编排**：v4/09 §九 与 v4/12 §4.3 明确规定
#    「跨域报表 / 看板是唯一允许跨域读的地方」—— 报表天然要横跨订单、设备、场地、分润，
#    强行经 Port 会退化成 N 次远程调用再在内存里 JOIN，比直接读更糟。
#    **但只豁免读**：这些包里一旦出现写操作（insert/update/delete），仍判违规 ——
#    跨域写必须经 Port，否则拆分时事务边界就断了（这正是 G2 守的东西）。
G3_READONLY_ORCHESTRATION = ('portal/report/', 'portal/dashboard/', 'operation/')
#
# 2) 演示种子：它的职责就是把各域的样例数据一次性灌进去，跨域是本分。
#    且默认不装配（`sharehub.seed.enabled=false`），不在任何生产代码路径上。
#    B4 会把它物理移到 `support/sharehub-seed`，届时这条豁免可以删掉。
G3_SEED_EXEMPT = ('seed/',)
# 写操作特征：MyBatis-Plus 的写方法 + 自定义 mapper 的常见写前缀
WRITE_CALL = re.compile(r'\.\s*(insert|update|delete|save|remove)\w*\s*\(')


def load_module_graph():
    """复用 module-graph.py 的模块与服务划分（唯一真值）。"""
    p = os.path.join(ROOT, 'backend/scripts/module-graph.py')
    spec = importlib.util.spec_from_file_location('mg', p)
    mg = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mg)
    return mg


def strip_comments(s):
    """剥注释但保留字符串字面量 —— SQL 就写在字符串里，剥错了就没得分析了。"""
    out, i, n = [], 0, len(s)
    while i < n:
        if s[i] in '"\'':
            q, j = s[i], i + 1
            # Java 文本块 """ ... """
            if s.startswith('"""', i):
                j = s.find('"""', i + 3)
                j = n if j < 0 else j + 3
                out.append(s[i:j]); i = j; continue
            while j < n:
                if s[j] == '\\':
                    j += 2; continue
                if s[j] == q:
                    j += 1; break
                j += 1
            out.append(s[i:j]); i = j
        elif s.startswith('/*', i):
            e = s.find('*/', i); i = n if e < 0 else e + 2; out.append(' ')
        elif s.startswith('//', i):
            e = s.find('\n', i); i = n if e < 0 else e; out.append(' ')
        else:
            out.append(s[i]); i += 1
    return ''.join(out)


def simplify_fqn(s):
    """把 `a.b.c.Foo` 归一成 `Foo`（注解与类型引用都适用）。

    必须做：检测器若只认简单名，任何人写全限定名就能绕过三个卡口 ——
    反向对照实测，三个用全限定名写的探针**一个都没被抓到**。
    只折叠「小写包名段 + 大写类名」的形式，不动 `obj.method()` 这类调用链。
    """
    return re.sub(r'\b(?:[a-z_][\w]*\s*\.\s*)+([A-Z]\w*)', r'\1', s)


def read_balanced(s, i, op='{', cl='}'):
    depth, j = 0, i
    while j < len(s):
        if s[j] == op:
            depth += 1
        elif s[j] == cl:
            depth -= 1
            if depth == 0:
                return s[i:j + 1], j + 1
        j += 1
    return s[i:], len(s)


def scan_classes(tbl2svc):
    """扫全仓 Java，建立：类名→服务、类名→字段(名→类型)、类名→方法体、实体→表。"""
    cls_svc, cls_fields, cls_methods, cls_file = {}, {}, {}, {}
    ent_table = {}

    files = list(walk_java())          # [(绝对路径, 短路径)]

    # 一轮：实体 → 表
    for p, _rel in files:
        src = simplify_fqn(strip_comments(io.open(p, encoding='utf-8').read()))
        for m in re.finditer(r'@TableName\s*\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                             r'(?:public\s+)?(?:final\s+)?class\s+(\w+)', src):
            tm = re.search(r'"([^"]+)"', m.group(1))
            if tm:
                ent_table[m.group(2)] = tm.group(1)

    # 二轮：类结构
    for p, rel in files:
        raw = io.open(p, encoding='utf-8').read()
        src = simplify_fqn(strip_comments(raw))
        for m in re.finditer(r'\b(?:public\s+|final\s+|abstract\s+)*(?:class|interface)\s+(\w+)', src):
            name = m.group(1)
            body_start = src.find('{', m.end())
            if body_start < 0:
                continue
            body, _ = read_balanced(src, body_start)
            cls_file[name] = rel
            # 字段：类型 名;  以及构造注入的参数
            fields = {}
            for fm in re.finditer(r'(?:private|protected|public)\s+(?:final\s+)?'
                                  r'([A-Z][\w.<>,\s]*?)\s+(\w+)\s*[;=]', body):
                fields[fm.group(2)] = fm.group(1).split('<')[0].strip().split('.')[-1]
            cls_fields[name] = fields
            # 方法体
            methods = {}
            for mm in re.finditer(r'(?:public|protected|private)\s+(?:static\s+)?'
                                  r'[\w<>,\[\]?.\s]+?\s+(\w+)\s*\([^;{)]*\)\s*(?:throws [\w,\s]+)?\{', body):
                st = body.find('{', mm.end() - 1)
                mb, _ = read_balanced(body, st)
                methods.setdefault(mm.group(1), []).append(mb)
            cls_methods[name] = methods
            cls_svc[name] = None
    # Mapper 是**接口、没有方法体**，靠「触及哪些实体」推断服务对它完全失效
    # （反向对照实测：注入 StlWithdrawalMapper 的探针没被 G3 抓到，就是栽在这）。
    # 改从 `extends BaseMapper<实体>` 的泛型参数取。
    mapper_ent = {}
    impls = defaultdict(set)          # 接口 → 实现类
    for p2, _rel2 in files:
        src2 = simplify_fqn(strip_comments(io.open(p2, encoding='utf-8').read()))
        for mm in re.finditer(r'interface\s+(\w+)\s+extends\s+\w*Mapper\s*<\s*(\w+)\s*>', src2):
            mapper_ent[mm.group(1)] = mm.group(2)
        # 接口 → 实现：**不建这张表，抽 Port 会让检测器变瞎** ——
        # 字段类型从具体类换成接口后，接口没有方法体，调用链在那里断掉，
        # 跨服务事务就「消失」了。重构不该有让卡口失明的副作用。
        for mm in re.finditer(r'class\s+(\w+)[^{]*?\bimplements\s+([\w,\s<>]+?)\s*\{', src2):
            for it in mm.group(2).split(','):
                it = it.split('<')[0].strip()
                if it:
                    impls[it].add(mm.group(1))
    return cls_svc, cls_fields, cls_methods, cls_file, ent_table, mapper_ent, dict(impls)


# ─────────────────────────── G1 跨模块 JOIN ───────────────────────────

def g1_cross_join(tbl2svc):
    bad = []
    for p, rel in walk_java():
        if True:
            src = simplify_fqn(strip_comments(io.open(p, encoding='utf-8').read()))
            if '@Select' not in src and '@Update' not in src and '@Delete' not in src:
                continue
            for m in re.finditer(r'@(?:Select|Update|Delete)\s*\(\s*("""(.*?)"""|"((?:[^"\\]|\\.)*)")',
                                 src, re.S):
                sql = (m.group(2) or m.group(3) or '')
                tables = set(re.findall(r'\b(?:FROM|JOIN)\s+`?(\w+)`?', sql, re.I))
                svcs = {tbl2svc[t] for t in tables if t in tbl2svc}
                svcs.discard(None)
                if len(svcs) > 1:
                    bad.append({'file': rel, 'tables': sorted(tables & set(tbl2svc)),
                                'services': sorted(svcs),
                                'whitelisted': rel in JOIN_WHITELIST})
    return bad


# ─────────────────────────── G2 跨服务事务（跟调用链） ───────────────────────────

# 写方法名前缀。跨服务**写**才是纪律二要防的（拆开后无法原子回滚）；
# 跨服务**读**是延迟/韧性问题，不是原子性问题 —— 两者必须分开报，
# 否则「授权闸门做一次跨服务查询」会和「跨服务写」混为一谈，
# 真正危险的那类就被噪音淹了。
WRITE_HINTS = ('insert', 'update', 'delete', 'save', 'remove', 'reassign', 'bump')


def g2_cross_tx(tbl2svc, cls_fields, cls_methods, cls_file, ent_table, impls):
    ent_svc = {e: tbl2svc.get(t) for e, t in ent_table.items()}

    def touched(cls, method, seen, depth):
        """返回该方法（含其调用链）触及的 {服务: {证据}}。"""
        out = defaultdict(set)
        if depth > 6 or (cls, method) in seen:
            return out
        seen = seen | {(cls, method)}
        is_write = any(h in method.lower() for h in WRITE_HINTS)
        for body in cls_methods.get(cls, {}).get(method, []):
            # 本方法直接触及的实体；标注是写还是读
            hit_write = is_write or bool(re.search(
                r'\.(insert|update|delete|updateById|deleteById|insertOrUpdate)\s*\(', body))
            for e, sv in ent_svc.items():
                if sv and re.search(r'\b%s\b' % e, body):
                    out[sv].add('%s%s#%s → %s' % ('W:' if hit_write else 'R:', cls, method, e))
            # 顺着字段调用继续走（跨类）
            for fname, ftype in cls_fields.get(cls, {}).items():
                # 字段是接口时展开到全部实现类，否则调用链在接口处断掉
                targets = [ftype] + sorted(impls.get(ftype, ()))
                targets = [t for t in targets if t in cls_methods]
                if not targets:
                    continue
                for cm in re.finditer(r'\b%s\s*\.\s*(\w+)\s*\(' % re.escape(fname), body):
                    for tgt in targets:
                        for sv, ev in touched(tgt, cm.group(1), seen, depth + 1).items():
                            out[sv] |= ev
            # 同类内的方法调用（`cascade(...)` / `this.cascade(...)`）——**必须跟**：
            # 首版漏了这一种，于是 AgentAssignmentServiceImpl#assign 报「无跨界」，
            # 因为它写 dev_cabinet 是经 AgentOwnershipSync#apply 再转同类私有方法 cascadeSite 完成的。
            # 一个只跟得动跨类调用的检测器，会把「藏在私有方法里的跨界」全部放过。
            for cm in re.finditer(r'(?<![\w.])(?:this\s*\.\s*)?(\w+)\s*\(', body):
                callee = cm.group(1)
                if callee in cls_methods.get(cls, {}) and callee != method:
                    for sv, ev in touched(cls, callee, seen, depth + 1).items():
                        out[sv] |= ev
        return out

    bad, warn = [], []
    for p, rel in walk_java():
        if True:
            src = simplify_fqn(strip_comments(io.open(p, encoding='utf-8').read()))
            if '@Transactional' not in src:
                continue
            cm = re.search(r'\b(?:public\s+|final\s+)*class\s+(\w+)', src)
            if not cm:
                continue
            cls = cm.group(1)
            for tm in re.finditer(r'@Transactional[^\n]*\n\s*(?:public\s+)?'
                                  r'[\w<>,\[\]?.\s]+?\s+(\w+)\s*\(', src):
                method = tm.group(1)
                svcs = touched(cls, method, set(), 0)
                if len(svcs) < 2:
                    continue
                # 只有「写触及 ≥2 个服务」才是违规；其余记为读跨界（警告）
                write_svcs = {k for k, v in svcs.items() if any(x.startswith('W:') for x in v)}
                rec = {'file': rel, 'method': '%s#%s' % (cls, method),
                       'services': {k: sorted(v)[:3] for k, v in sorted(svcs.items())},
                       'writeServices': sorted(write_svcs)}
                (bad if len(write_svcs) > 1 else warn).append(rec)
    return bad, warn


# ─────────────────────────── G3 跨服务注入 ───────────────────────────

def g3_cross_injection(tbl2svc, cls_fields, cls_methods, cls_file, ent_table, mapper_ent):
    """注入了别的服务的**实现类**（*ServiceImpl / *Mapper）即违规，应经 api 层 Port 接口。

    当前是单模块，服务归属按「该类触及哪些表」反推 —— 拆成 svc-* 后可改为按 Maven 模块判定，
    那时更准。现在这版会漏掉「不直接碰表的纯编排类」，是已知局限，写在报告里不藏着。
    """
    ent_svc = {e: tbl2svc.get(t) for e, t in ent_table.items()}

    def svc_of(cls):
        # Mapper 先走泛型参数（接口无方法体，实体扫描法对它无效）
        if cls in mapper_ent:
            return ent_svc.get(mapper_ent[cls])
        hits = defaultdict(int)
        for bodies in cls_methods.get(cls, {}).values():
            for b in bodies:
                for e, sv in ent_svc.items():
                    if sv and re.search(r'\b%s\b' % e, b):
                        hits[sv] += 1
        return max(hits, key=hits.get) if hits else None

    cache = {}

    def svc_cached(c):
        if c not in cache:
            cache[c] = svc_of(c)
        return cache[c]

    def exempt_reason(cls):
        """返回豁免理由；None = 不豁免。见 G3_READONLY_ORCHESTRATION / G3_SEED_EXEMPT。"""
        rel = cls_file.get(cls, '')
        if rel.startswith(G3_SEED_EXEMPT):
            return '演示种子（默认不装配，B4 移入 support/sharehub-seed）'
        if rel.startswith(G3_READONLY_ORCHESTRATION):
            # 只豁免只读：本类任一方法体里出现写调用就不再豁免
            for bodies in cls_methods.get(cls, {}).values():
                for b in bodies:
                    if WRITE_CALL.search(b):
                        return None          # 有写 → 仍判违规
            return 'app 层跨域只读编排（v4/09 §九）'
        return None

    bad, exempted = [], []
    for cls, fields in cls_fields.items():
        mine = svc_cached(cls)
        if not mine:
            continue
        why = exempt_reason(cls)
        for fname, ftype in fields.items():
            if not re.search(r'(ServiceImpl|Mapper)$', ftype):
                continue
            theirs = svc_cached(ftype)
            if theirs and theirs != mine:
                rec = {'class': cls, 'file': cls_file.get(cls, '?'),
                       'field': '%s %s' % (ftype, fname),
                       'from': mine, 'to': theirs}
                if why:
                    rec['exempt'] = why
                    exempted.append(rec)
                else:
                    bad.append(rec)
    return bad, exempted


# ─────────────────────────── G4 common 零业务依赖 ───────────────────────────

def g4_common_purity():
    """`powerbank-common` 不许 import 任何业务包（ADR-017 S1）。

    这条一旦破例，**全部 svc-* 都会通过 common 间接依赖那个业务包**，
    拆分就地失效 —— 而且是静默失效：编译照过、测试照绿，只有真拆的时候才发现拆不动。
    Maven 层面的 enforcer 拦不住这个（common 不声明业务依赖也能 import 同仓的类，
    因为拆分前它们还在一个 jar 里），所以只能靠源码扫描。
    """
    # 2026-09-23：此前写的是 `powerbank-common` —— 模块早已改名 `sharehub-common`，
    # 目录不存在就静默 return []，于是 G4 从改名那天起**一次都没真正跑过**。
    root = os.path.join(ROOT, 'backend/sharehub-common/src/main/java')
    if not os.path.isdir(root):
        raise SystemExit('arch-guard: 找不到 common 模块源码根 ' + root + ' —— 模块改名后请同步此处')
    allow = ('ai.neargo.sharehub.common', 'ai.neargo.sharehub.auth')
    bad = []
    for base, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith('.java'):
                continue
            p2 = os.path.join(base, f)
            for line in io.open(p2, encoding='utf-8'):
                line = line.strip()
                if not line.startswith('import ai.neargo.sharehub.'):
                    continue
                if not line.startswith(tuple('import ' + a for a in allow)):
                    bad.append({'file': os.path.relpath(p2, ROOT), 'import': line})
    return bad


# ─────────────────────────── 主 ───────────────────────────

def main():
    strict = '--strict' in sys.argv
    mg = load_module_graph()
    # 表 → 服务（两跳：表 → 模块 → 服务，模块划分复用 module-graph.py）
    tbl2svc = {}
    mod2svc = {m: s for s, ms in mg.SERVICES.items() for m in ms}
    import subprocess
    rows = subprocess.run(['mysql', '-upowerbank', '-ppowerbank', '-N', '-B', 'pb_core', '-e',
                           "SELECT table_name FROM information_schema.tables "
                           "WHERE table_schema='pb_core'"],
                          capture_output=True, text=True).stdout.split()
    for t in rows:
        m = mg.module_of(t)
        if m:
            tbl2svc[t] = mod2svc.get(m)

    print('服务划分：%s' % ' · '.join('%s(%d 模块)' % (s, len(ms)) for s, ms in mg.SERVICES.items()))
    print('表 → 服务映射：%d 张\n' % len(tbl2svc))

    cls_svc, cls_fields, cls_methods, cls_file, ent_table, mapper_ent, impls = scan_classes(tbl2svc)
    print('扫描：%d 个类 · %d 个实体 · %d 个 mapper · %d 个接口有实现\n'
          % (len(cls_fields), len(ent_table), len(mapper_ent), len(impls)))

    n_bad = 0

    # G1
    j = g1_cross_join(tbl2svc)
    real_j = [x for x in j if not x['whitelisted']]
    print('── G1 跨模块 JOIN ──')
    print('   命中 %d 处，白名单豁免 %d 处 → **违规 %d**' % (len(j), len(j) - len(real_j), len(real_j)))
    for x in real_j:
        print('   ⚠️ %s' % x['file'])
        print('      %s  跨越 %s' % (' ⋈ '.join(x['tables']), ' / '.join(x['services'])))
    n_bad += len(real_j)

    # G2
    t, tw = g2_cross_tx(tbl2svc, cls_fields, cls_methods, cls_file, ent_table, impls)
    print('\n── G2 跨服务事务（跟调用链，深度 ≤6）──')
    print('   **写跨界 %d（违规）** · 读跨界 %d（警告，不计违规）' % (len(t), len(tw)))
    for x in tw:
        print('   ℹ️ %s   仅读跨界：%s' % (x['method'], ' / '.join(x['services'])))
    for x in t:
        print('   ⚠️ %s   （%s）' % (x['method'], x['file']))
        for sv, ev in x['services'].items():
            print('      %-16s %s' % (sv, '；'.join(ev)))
    n_bad += len(t)

    # G3
    i3, i3_exempt = g3_cross_injection(tbl2svc, cls_fields, cls_methods, cls_file, ent_table, mapper_ent)
    # ── 棘轮：台账外的新豁免一律判失败 ──
    # 没有这一步，「豁免」就是一张无限额的白条：下次有人跨域注入，只要把类放进
    # operation/ 或 seed/ 就自动免检，卡口又回到永远全绿。
    ledger = os.path.join(ROOT, 'backend/known-arch-exemptions.txt')
    known = set()
    if os.path.isfile(ledger):
        for line in io.open(ledger, encoding='utf-8'):
            line = line.strip()
            if line and not line.startswith('#'):
                known.add(line)
    new_exempt = [x for x in i3_exempt
                  if '%s 注入 %s' % (x['class'], x['field']) not in known]

    print('\n── G3 跨服务注入实现类 ──')
    print('   **违规 %d**（豁免 %d，台账 %d）' % (len(i3), len(i3_exempt), len(known)))
    if new_exempt:
        print('   ❌ 台账外的新豁免 %d 处 —— 要么改用 Port，要么把理由写进 '
              'known-arch-exemptions.txt：' % len(new_exempt))
        for x in new_exempt:
            print('      + %s 注入 %s（%s → %s）' % (x['class'], x['field'], x['from'], x['to']))
        n_bad += len(new_exempt)
    for x in i3:
        print('   ⚠️ %s 注入了 %s' % (x['class'], x['field']))
        print('      %s → %s   应改为经 api-%s 的 Port 接口' % (x['from'], x['to'], x['to']))
    if i3_exempt and '-v' in sys.argv:
        for x in i3_exempt:
            print('   ○ 豁免：%s 注入 %s —— %s' % (x['class'], x['field'], x['exempt']))
    n_bad += len(i3)

    g4 = g4_common_purity()
    print('\n── G4 common 零业务依赖 ──')
    print('   **违规 %d**' % len(g4))
    for x in g4:
        print('   ⚠️ %s' % x['file'])
        print('      %s   —— common 依赖业务包会让全部 svc-* 间接依赖它，拆分静默失效' % x['import'])
    n_bad += len(g4)

    print('\n' + ('❌ 共 %d 处违规' % n_bad if n_bad else '✅ 四个卡口全部通过'))
    if strict and n_bad:
        sys.exit(1)


if __name__ == '__main__':
    main()
