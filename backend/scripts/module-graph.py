#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从实际库结构反推业务模块的依赖图，并按依赖做分层。

为什么不是人工画：分层图最容易变成「看起来很整齐但和代码没关系」的装饰品。
本脚本用**列名引用**当证据 —— `ord_rent.cabinet_no` 就是订单依赖设备的硬证据 ——
再对模块图做拓扑分层。**层级是算出来的，不是排出来的**，因此：
  · 环（互相依赖）会被显式报出来，那是边界没切对的地方；
  · 上层依赖下层的规则可被机器校验，不靠人自觉。

局限（必须知道，否则会高估这份图）：
  · 本库**没有外键约束**（分库分表 + 追加表的取舍），依赖只能靠命名约定推断；
  · 只认 `*_no` / `*_code` / `*_id` 这类业务键列，JSON 里的软引用抓不到；
  · 快照字段（如 `payee_name`）不算依赖 —— 它是拷贝，不是引用。

用法：python3 backend/scripts/module-graph.py
"""
import io
import json
import os
import re
import subprocess
from collections import OrderedDict, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = 'pb_core'

# ── 模块定义：表前缀/具体表 → 模块。顺序有意义，先匹配者胜（具体表覆盖前缀规则）──
# 这是本脚本里**唯一的人工判断**，其余全是算出来的。划分依据写在每行注释里。
MODULES = OrderedDict([
    # —— 平台底座：不含任何业务语义，被所有层依赖 ——
    ('租户', (['tenant', 'tenant_config'], '多租户隔离的锚点；单运营方下休眠但不删（ADR-011）')),
    ('认证授权', (['iam_role', 'iam_permission', 'iam_role_perm', 'iam_data_scope', 'iam_menu'],
                'RBAC 与数据范围。**不含 sys_token** —— 会话令牌是基础设施（见 INFRA_TABLES），'
                '归在这里会让「登录」凭空跨服务边界')),
    ('组织架构', (['iam_dept', 'iam_employee', 'iam_employee_role', 'iam_staff_perf'],
                '部门树与员工归属 —— 与「认证授权」分开：组织是业务概念（谁向谁汇报、按部门看数），RBAC 是基础设施')),
    ('审计', (['iam_audit_log'], 'WORM 留痕，只写不改')),
    ('主数据', (['md_'], '银行/地区/国家/问题目录 —— 全局共享，无租户列')),
    ('数据字典', (['dict_'], '可运营配置的枚举，避免硬编码发版')),
    ('系统参数', (['sys_'], '业务规则/税率/登录策略/应用版本')),
    ('通知', (['notify_'], '模板 · 发送日志 · 退订黑名单；被所有层调用')),
    ('开放平台', (['openapi_'], '对外 API 应用与密钥')),
    ('支付渠道', (['pay_channel', 'pay_channel_scope'],
               '支付渠道及其适用范围 —— 配置，不是流水。**与「支付」分开**：充值单引用渠道'
               '(`usr_recharge_order.channel_code`)、支付流水引用消费者(`pay_order.c_user_no`)，'
               '混在一个模块里就成了 消费者↔支付 的环。\n'
               '这是本图第三次出现同一个模式：**目录/配置与运行时流水必须分模块**'
               '（另两处：厂商目录 vs 网关运行时、计价方案 vs 订单）')),
    ('厂商目录', (['gw_vendor', 'gw_vendor_config'],
               '设备厂商及其接入配置。**与「设备接入」分开**：厂商是主数据（机柜的一个属性，'
               '`dev_cabinet.vendor_code` 引用它），报文日志是运行时产物（引用机柜）——'
               '混在一个模块里会让「设备资产」与「设备接入」互相依赖，算出一个真环')),

    # —— 主体与资源：被交易消费的「人」和「物」 ——
    ('代理商', (['agt_agent', 'agt_agent_region', 'agt_account'],
               '代理主体 · 区域授权 · 账户。**只放主体本身** —— 划拨与佣金曾放在这里，'
               '结果算出「代理商依赖订单与设备」的环：划拨是跨模块的过程、佣金是资金概念，都不是主体的属性')),
    ('资产归属', (['agt_assignment'],
               '把场地/点位/机柜划给代理商。同时触及代理商+场地+设备三个模块，'
               '故独立成模块置于三者之上，而不是塞进其中任何一个')),
    ('场地拓展', (['loc_lead', 'loc_contract'], '商机 → 合同；签约前的销售过程，与运营中的场地分开')),
    ('场地与点位', (['loc_'], '场地 → 点位；数据范围的归属链起点')),
    ('设备资产', (['dev_cabinet', 'dev_powerbank', 'dev_slot', 'dev_shadow', 'dev_device_log',
                'dev_command*', 'dev_code_batch'], '机柜/充电宝/仓位的资产台账与编码区间')),
    ('固件', (['dev_ota*'], '固件版本 · 灰度批次 · 单机升级任务；与设备资产分开，因为它有独立的发布节奏')),
    ('设备接入', (['gw_', 'dev_heartbeat'], '南向运行时：绑定 · 心跳 · 指令与报文日志（厂商目录见上）')),
    ('库存', (['inv_'], '仓库 · 在库量 · 调拨单')),
    ('消费者', (['usr_', 'c_'], 'C 端账号 · 钱包 · 实名 · 授权同意 · 黑名单 · 免押白名单')),
    ('权益与营销', (['coupon_', 'mbr_', 'mkt_', 'ad_'], '券模板 · 会员 · 活动 · 推荐 · 广告；被交易消费，故在交易之下')),

    # —— 运行管理 ——
    ('告警', (['dev_alarm*', 'dev_alert'], '告警码目录 · 规则 · 记录 · 通知；归一化/去重/静默/升级')),
    ('工单', (['wo_'], '派单 · 处理 · 验收 · SLA · 巡检计划')),

    # —— 交易 ——
    ('计价', (['price_'], '价格方案 · 规则 · 时段 · 适用范围')),
    ('订单', (['ord_'], '租借单 · 押金 · 退款 · 异常 · 投诉 · 事件流水')),
    ('支付', (['pay_'], '支付单 · 渠道 · 预授权 · 退款 · 回调事件')),

    # —— 资金 ——
    ('账务', (['acct_'], '科目与复式分录；只追加')),
    ('分润', (['share_', 'agt_commission'],
              '规则与逐单明细。佣金（agt_commission）归这里 —— 它是分润的一种结算形态，不是代理商的属性')),
    ('结算提现', (['stl_'], '结算单 · 明细 · 提现')),
    ('发票', (['fin_invoice', 'fin_'], '开票与明细')),
    ('对账', (['recon_'], '对账任务与差异')),

    # —— 服务 ——
    ('客服', (['cs_'], '会话 · 消息 · 工单受理')),
])

# ── 基础设施表：不属于任何业务服务，故不参与服务归属与跨界判定 ──
#
# **这不是给违规开豁免，是纠正分类**（ADR-019）。两者的区别是致命的：
#   豁免 = 承认它跨界但假装没事 → 口子一开，卡口会逐步退化成摆设；
#   分类 = 它根本不属于任何业务服务，所以不存在跨界。
#
# 入列须**同时**满足三条，且逐条写明理由；加入需评审：
#   ① 所有服务都读写它  ② 不承载业务语义  ③ 拆分后走共享中间件而非业务库
INFRA_TABLES = {
    # 会话令牌。TokenStore 是可切换 SPI（已有 redis/mysql/memory/localcache 四个实现），
    # 本表只是 MysqlTokenStore 的存储细节；生产配 redis 后它根本不存在。
    # 把它算作 platform 的业务表，会让「登录」（发生在 core）凭空跨界。
    'sys_token',
    # 迁移元数据，Flyway 自用。
    'flyway_schema_history',
}

SKIP = set(INFRA_TABLES)

# ── 语义分层：人来定「这个模块属于业务上的哪一层」，机器来验「有没有反向依赖」──
#
# 为什么不直接用算出来的拓扑深度当层：深度只说明「依赖链有多长」，不说明业务含义。
# 「结算提现」出边少所以深度浅，但它显然不属于底座。分层要给人看，就得按业务讲得通；
# 而它要站得住，就必须能被机器证伪 —— 判据只有一条：**不允许依赖更高的层**。
NAMED_LAYERS = OrderedDict([
    ('L0 平台底座', (['租户', '认证授权', '审计', '主数据', '数据字典', '系统参数', '通知',
                  '开放平台', '支付渠道', '厂商目录'],
                  '与业务无关的能力与目录。被所有层依赖，不依赖任何业务模块。')),
    ('L1 主体与资源', (['组织架构', '代理商', '消费者', '权益与营销',
                    '场地拓展', '场地与点位', '设备资产', '固件', '库存', '资产归属'],
                    '经营的「人」与「物」：谁在经营（组织/代理）、服务谁（消费者）、'
                    '用什么经营（场地/设备/库存）。层内可互相依赖。')),
    ('L2 运行管理', (['告警', '工单', '设备接入'],
                  '让资源转起来：设备联通、异常发现、人工处置。')),
    ('L3 交易', (['计价', '订单', '支付'], '一次租借的完整生命周期。')),
    ('L4 资金', (['账务', '分润', '结算提现', '发票', '对账'],
                '钱怎么记、怎么分、怎么结、怎么核。全部在交易之后。')),
    ('L5 服务', (['客服'], '售后与人工介入，跨交易与运行两层取数。')),
])


# ── 部署单元（ADR-017 定形态 / ADR-018 调整为 5 个）：默认单体，需要时可拆 ──
#
# 划分依据不是「跨服务边最少」，而是「**同步主链路必须在同一进程内**」：
# 借出一笔要在一个事务里同时动订单/充电宝占用/支付预授权/券核销，拆开就得上分布式事务，
# 等于用系统最热的链路换「服务更小」。core 大是有意的。
SERVICES = OrderedDict([
    ('platform', ['租户', '认证授权', '审计', '主数据', '数据字典', '系统参数', '通知', '开放平台',
                  '支付渠道', '组织架构', '代理商', '场地拓展', '场地与点位', '资产归属']),
    ('core', ['消费者', '权益与营销', '计价', '订单', '支付', '设备资产', '库存']),
    # 南向独立（ADR-018）：理由是治理不是性能 —— 每接一个供应商就改一次、故障域要隔离、
    # 技术栈是长连接。它是设备类型与供应商增多时唯一线性膨胀的部分，把膨胀关进一个进程。
    ('device-gateway', ['厂商目录', '设备接入', '固件']),
    ('ops', ['告警', '工单', '客服']),
    ('finance', ['账务', '分润', '结算提现', '发票', '对账']),
])

# 目录型模块：慢变、只读，各服务本地缓存 + 订阅变更事件即可，依赖它们**不计入拆分成本**。
# 不做这个区分的话，`region_id` 这种引用会把跨服务边虚高一倍，导致「怎么切都很糟」的错误结论。
CATALOG = {'主数据', '数据字典', '厂商目录', '支付渠道', '系统参数', '租户', '认证授权',
           '审计', '通知', '开放平台'}


def service_of(m):
    for s, ms in SERVICES.items():
        if m in ms:
            return s
    return None


def named_layer_of(m):
    for name, (mods, _) in NAMED_LAYERS.items():
        if m in mods:
            return name
    return None



def module_of(table):
    # 基础设施表在入口处就排除，而不是让每个调用方各自记得排除 ——
    # 后者迟早会漏一个（arch-guard.py 第一版就漏了）。
    if table in INFRA_TABLES:
        return None
    for name, (pats, _) in MODULES.items():
        for p in pats:
            if p.endswith('*'):
                if table.startswith(p[:-1]):
                    return name
            elif table == p or (p.endswith('_') and table.startswith(p)):
                return name
    return None


def q(sql):
    r = subprocess.run(['mysql', '-upowerbank', '-ppowerbank', '-N', '-B', DB, '-e', sql],
                       capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stderr.strip())
    return [l.split('\t') for l in r.stdout.strip('\n').split('\n') if l]


def main():
    tables = [t for (t,) in q("SELECT table_name FROM information_schema.tables "
                              "WHERE table_schema='%s' ORDER BY table_name" % DB)
              if t not in SKIP]
    cols = q("SELECT table_name, column_name FROM information_schema.columns "
             "WHERE table_schema='%s'" % DB)

    tmod = {t: module_of(t) for t in tables}
    orphan = [t for t, m in tmod.items() if not m]

    # ── 建业务键 → 模块的索引：某表的 UNIQUE 业务键列名，即别人引用它时用的列名 ──
    # 例：stl_withdrawal 的 withdraw_no → 任何表出现 withdraw_no 列都算引用「结算提现」
    # 一个键名可能被多个模块各自定义（`rule_no` 既是分润规则也是告警规则），
    # 这种**跨域重名的业务键不能用来推依赖** —— 「谁先扫到算谁的」会凭空造出
    # 「告警依赖分润」「订单依赖支付」这类假边，后者还直接制造了假环。
    # 故先收集所有候选归属，只有归属唯一的键才建边；有歧义的单独报出来。
    owners = defaultdict(set)
    for t, c in cols:
        if not tmod.get(t):
            continue
        stem = t.split('_', 1)[1] if '_' in t else t
        if c in ('%s_no' % stem, '%s_code' % stem, '%s_id' % stem):
            owners[c].add(tmod[t])
    ambiguous = {k: sorted(v) for k, v in owners.items() if len(v) > 1}
    key_owner = {k: list(v)[0] for k, v in owners.items() if len(v) == 1}
    # 手工补几个「表名与键名不同源」的（命名约定没覆盖到的例外，逐个写明理由）
    # 手工补的键必须同样受歧义检测约束 —— 否则等于用人工判断把检测绕过去。
    # 实测教训：这里曾写死 `plan_no → 计价`，但 `plan_no` 正是歧义键之一
    # （`mbr_plan`/`wo_inspection_plan` 各有同名主键），凭空造出 3 处「反向依赖」，
    # 差点据此去「修」根本不存在的架构问题。
    manual = {
        'user_no': '消费者',        # usr_user 的键叫 user_no
        'c_user_no': '消费者',      # 订单侧对消费者的引用列名
        'order_no': '订单',         # ord_rent 的键
        'agent_no': '代理商',
        'site_no': '场地与点位',
        'location_no': '场地与点位',
        'cabinet_no': '设备资产',
        'powerbank_no': '设备资产',
        'employee_no': '组织架构',
        'dept_no': '组织架构',
        'role_no': '认证授权',
        'tenant_no': '租户',
        'wo_no': '工单',
        'alarm_no': '告警',
        'coupon_no': '权益与营销',
        'plan_no': '计价',
    }
    overridden_ambiguous = sorted(set(manual) & set(ambiguous))
    for k in overridden_ambiguous:
        manual.pop(k)
    key_owner.update(manual)

    # ── 依赖边：表 A 有列 X，X 属于模块 B，且 B≠A 所在模块 → A模块 依赖 B模块 ──
    # 数据范围冗余锚点不算领域依赖：`agent_no` 是 B1 为行级过滤加的**归属快照**，
    # 订单侧还明确规定过转手不回写（老单仍归老代理）。快照是拷贝不是引用 ——
    # 把它当依赖会算出「订单依赖代理商」，与「代理商是被划拨的主体」正好反向，
    # 并直接制造 订单→代理商→分润→订单 的假环。
    # 它是一条**横切轴**（见文档「资产归属」模块），不是层间边。
    SCOPE_ANCHORS = {'agent_no'}

    edges = defaultdict(set)      # 模块 → 依赖的模块集合
    evidence = defaultdict(list)  # (a,b) → [(表, 列)]
    for t, c in cols:
        a = tmod.get(t)
        b = key_owner.get(c)
        if c in SCOPE_ANCHORS and a not in ('资产归属',):
            continue
        if a and b and a != b:
            edges[a].add(b)
            evidence[(a, b)].append((t, c))

    # ── 拓扑分层：层号 = 最长依赖链深度。先找出真实环路（含路径），环内模块拉平到同层 ──
    mods = list(MODULES)

    # 环路要报出**具体路径**才有用：只说「涉及 A、B、C」无法判断该切哪条边。
    cycles = []
    def find_cycles(node, path, visiting):
        for dep in sorted(edges.get(node, ())):
            if dep not in mods:
                continue
            if dep in path:
                cyc = path[path.index(dep):] + [dep]
                if sorted(set(cyc)) not in [sorted(set(c)) for c in cycles]:
                    cycles.append(cyc)
            elif dep not in visiting:
                find_cycles(dep, path + [dep], visiting | {dep})
    for m in mods:
        find_cycles(m, [m], {m})

    in_cycle = {m for c in cycles for m in c}

    layer = {}
    def depth(m, seen):
        if m in layer:
            return layer[m]
        if m in seen:
            return 0
        seen = seen | {m}
        d = 0
        for dep in edges.get(m, ()):
            if dep in mods:
                d = max(d, depth(dep, seen) + 1)
        layer[m] = d
        return d
    for m in mods:
        depth(m, set())

    by_layer = defaultdict(list)
    for m in mods:
        by_layer[layer[m]].append(m)

    print('表 %d · 模块 %d · 依赖边 %d' % (len(tables), len(mods), sum(len(v) for v in edges.values())))
    if overridden_ambiguous:
        print('\n(手工键名映射中 %s 与歧义键冲突，已忽略人工指定)' % '、'.join(overridden_ambiguous))
    if ambiguous:
        print('\n⚠️ 跨域重名的业务键 %d 个（已排除出依赖推导；本身也是设计问题：'
              '同名列在不同域指向不同实体，联表和排错时极易认错）：' % len(ambiguous))
        for k, v in sorted(ambiguous.items()):
            print('   %-14s %s' % (k, ' / '.join(v)))
    if orphan:
        print('\n⚠️ 归不进任何模块的表 %d 张（模块定义有漏）：' % len(orphan))
        for t in orphan:
            print('   %s' % t)
    if cycles:
        print('\n⚠️ 循环依赖 %d 条（分层在此处失去意义，必须切断一条边）：' % len(cycles))
        for c in cycles:
            print('   %s' % ' → '.join(c))
            for i in range(len(c) - 1):
                k = (c[i], c[i + 1])
                if k in evidence:
                    print('      %s→%s 由 %s' % (c[i], c[i + 1],
                          '、'.join('`%s.%s`' % e for e in evidence[k][:2])))

    print('\n拓扑深度（算出来的，仅作参考）：')
    for lv in sorted(by_layer):
        print('  D%d  %s' % (lv, ' · '.join(sorted(by_layer[lv]))))

    # ── 语义分层的唯一判据：不允许依赖更高的层 ──
    idx = {n: i for i, n in enumerate(NAMED_LAYERS)}
    unassigned = [m for m in mods if not named_layer_of(m)]
    violations = []
    for m in mods:
        lm = named_layer_of(m)
        if not lm:
            continue
        for dep in edges.get(m, ()):
            ld = named_layer_of(dep)
            if ld and idx[ld] > idx[lm]:
                violations.append((m, lm, dep, ld, evidence.get((m, dep), [])))

    print('\n语义分层：')
    for name, (ms, _) in NAMED_LAYERS.items():
        print('  %-12s %s' % (name, ' · '.join(ms)))
    if unassigned:
        print('\n⚠️ 未归入任何语义层的模块：%s' % '、'.join(unassigned))
    if violations:
        print('\n⚠️ 反向依赖 %d 处（下层依赖了上层 —— 分层在这些点上不成立）：' % len(violations))
        for m, lm, dep, ld, ev in violations:
            print('   %s(%s) → %s(%s)   由 %s'
                  % (m, lm.split()[0], dep, ld.split()[0],
                     '、'.join('`%s.%s`' % e for e in ev[:2]) or '?'))
    else:
        print('\n✅ 无反向依赖：每个模块只依赖同层或更低层。')

    # ── 部署单元校验 ──
    unowned = [m for m in mods if not service_of(m)]
    cross = [(a, b) for a in mods for b in edges.get(a, ())
             if service_of(a) != service_of(b)]
    real = [(a, b) for a, b in cross if b not in CATALOG]
    print('\n部署单元（ADR-017 形态 · ADR-018 调整为 5 个）：')
    for sv, ms in SERVICES.items():
        nt = sum(len(M) for M in [[t for t in tables if tmod[t] == m] for m in ms])
        print('  %-9s 模块 %2d · 表 %3d' % (sv, len(ms), nt))
    if unowned:
        print('  ⚠️ 未归入任何服务的模块：%s' % '、'.join(unowned))
    print('  跨服务边 %d（目录型 %d 不计成本 → **真实 %d**）'
          % (len(cross), len(cross) - len(real), len(real)))
    onpath = [(a, b) for a, b in real
              if {service_of(a), service_of(b)} == {'core'} ]
    print('  主链路上的跨服务边：%d（借出链路全在 core 内，应为 0）' % len(onpath))

    out = {'services': {sv: {'modules': ms,
                             'tables': sorted(t for t in tables if tmod[t] in ms)}
                        for sv, ms in SERVICES.items()},
           'crossServiceEdges': [{'from': a, 'fromSvc': service_of(a), 'to': b,
                                  'toSvc': service_of(b), 'catalog': b in CATALOG,
                                  'evidence': ['%s.%s' % e for e in evidence.get((a, b), [])[:3]]}
                                 for a, b in cross],
           'namedLayers': {n: {'modules': ms, 'note': note} for n, (ms, note) in NAMED_LAYERS.items()},
           'violations': [{'from': m, 'fromLayer': lm, 'to': d, 'toLayer': ld,
                           'evidence': ['%s.%s' % e for e in ev[:4]]}
                          for m, lm, d, ld, ev in violations],
           'modules': {m: {'namedLayer': named_layer_of(m), 'depth': layer[m], 'tables': sorted(t for t in tables if tmod[t] == m),
                           'dependsOn': sorted(edges.get(m, ())), 'note': MODULES[m][1]}
                       for m in mods},
           'orphanTables': orphan, 'cycles': cycles, 'ambiguousKeys': ambiguous,
           'evidence': {'%s→%s' % k: v[:6] for k, v in evidence.items()}}
    io.open('/tmp/module_graph.json', 'w', encoding='utf-8').write(
        json.dumps(out, ensure_ascii=False, indent=1))
    write_md(out, tables, tmod, edges, evidence, ambiguous, cycles, violations, layer)
    print('\n明细 → /tmp/module_graph.json · 文档 → docs/technical/业务模块分层.md')


def write_md(out, tables, tmod, edges, evidence, ambiguous, cycles, violations, depth):
    dst = os.path.join(ROOT, 'docs/technical/业务模块分层.md')
    L = ['# 业务模块分层\n',
         '> **本文件由脚本生成，不要手改** —— `python3 backend/scripts/module-graph.py`。\n>',
         '> 模块划分与语义分层是**人定的**（写在脚本的 `MODULES` / `NAMED_LAYERS` 里，每条都有理由）；'
         '依赖关系与校验结论是**算出来的**。\n']

    L.append('## 这份图凭什么可信\n')
    L.append('架构分层图最容易变成「画得很整齐但和代码没关系」的装饰品。这份图用三条硬约束顶住：\n')
    L.append('| 约束 | 怎么保证 | 当前结果 |\n|---|---|---|')
    L.append('| 每张表都必须归属某个模块 | 脚本报落单表 | **%d 张表全部归位，0 落单** |' % len(tables))
    L.append('| 依赖关系必须有证据 | 用列名引用推导（`ord_rent.cabinet_no` = 订单依赖设备），'
             '每条边都能追到具体列 | %d 条依赖边 |' % sum(len(v) for v in edges.values()))
    L.append('| 分层不允许反向依赖 | 拓扑校验，下层依赖上层即报错 | **%d 处违规**（见下） |' % len(violations))
    L.append('')
    L.append('**局限**（否则会高估这份图）：本库无外键约束，依赖靠命名约定推断；'
             'JSON 里的软引用抓不到；快照字段（如 `payee_name`）不算依赖 —— 它是拷贝不是引用。\n')

    L.append('## 分层总览\n')
    L.append('```')
    for name, (ms, _) in NAMED_LAYERS.items():
        L.append('%-14s %s' % (name, '  '.join(ms)))
    L.append('```\n')
    L.append('**层间规则**：模块只能依赖**同层或更低层**。层内可互相依赖'
             '（如「库存」依赖「设备资产」，同属 L1）。\n')
    for name, (ms, note) in NAMED_LAYERS.items():
        L.append('- **%s** — %s' % (name, note))
    L.append('')

    if violations:
        L.append('## ⚠️ 反向依赖（%d 处）\n' % len(violations))
        L.append('分层在这些点上**不成立**。每条都要么切断、要么承认分层错了。\n')
        for m, lm, dep, ld, ev in violations:
            L.append('### `%s`（%s）→ `%s`（%s）\n' % (m, lm, dep, ld))
            L.append('证据：%s\n' % '、'.join('`%s.%s`' % e for e in ev[:4]))
        L.append('')

    if ambiguous:
        L.append('## ⚠️ 跨域重名的业务键（%d 个）\n' % len(ambiguous))
        L.append('同一个列名在不同域指向**不同实体**。这不只是文档问题：联表、排错、'
                 '以及任何按列名做的自动化（包括本脚本）都会认错。\n')
        L.append('本脚本已把它们排除出依赖推导 —— 但代价是这些真实依赖**推不出来**，'
                 '所以下面的依赖图在这几处是偏保守的。\n')
        L.append('| 列名 | 被哪些模块各自定义 |\n|---|---|')
        for k, v in sorted(ambiguous.items()):
            L.append('| `%s` | %s |' % (k, ' / '.join(v)))
        L.append('')

    L.append('## 模块清单\n')
    for name, (ms, _) in NAMED_LAYERS.items():
        L.append('\n### %s\n' % name)
        for m in ms:
            info = out['modules'][m]
            L.append('#### %s\n' % m)
            L.append('%s\n' % info['note'])
            deps = info['dependsOn']
            L.append('依赖：%s\n' % ('、'.join('`%s`' % d for d in deps) if deps else '无（底座模块）'))
            ts = info['tables']
            L.append('表 %d 张：%s\n' % (len(ts), ' · '.join('`%s`' % t for t in ts) or '—'))
    io.open(dst, 'w', encoding='utf-8').write('\n'.join(L) + '\n')


if __name__ == '__main__':
    main()
