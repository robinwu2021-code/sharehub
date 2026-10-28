#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成服务端代码清单（docs/technical/服务端代码清单.md）：每个 Java 类的「现包 → 目标包」。

目标包规划见手写文档 docs/technical/服务端包名规划.md；本脚本只做一件事 ——
把规划里的归位规则逐条套到**实际源码**上，列出每个类该去哪、动作是什么。

为什么用脚本而不是手写清单：1100 个类，手写的清单第二周就过期。规则写在下面的 RULES 里，
代码变了重跑即可；**任何一个类没被规则命中，脚本以非零退出** —— 新增包时必须同步补规则，
否则清单会悄悄漏类。

用法：python3 backend/scripts/gen-code-inventory.py
"""
import io
import os
import re
import sys
from collections import OrderedDict, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BACKEND = os.path.join(ROOT, 'backend')
OUT = os.path.join(ROOT, 'docs/technical/服务端代码清单.md')
PKG_DIR = 'src/main/java/ai/neargo/sharehub'
BASE = 'ai.neargo.sharehub'

# ── 目标顶层包 → (业务模块, 目标 Maven 模块)。顺序即文档里的章节顺序。
TOP = OrderedDict([
    ('operator',    ('商家管理', 'sharehub-svc-platform')),
    ('network',     ('网点管理', 'sharehub-svc-platform')),
    ('system',      ('系统管理', 'sharehub-svc-platform')),
    ('device',      ('设备管理', 'sharehub-svc-core')),
    ('sales',       ('销售管理', 'sharehub-svc-core')),
    ('consumer',    ('用户管理', 'sharehub-svc-core')),
    ('ops',         ('运维管理', 'sharehub-svc-ops')),
    ('finance',     ('财务管理', 'sharehub-svc-finance')),
    ('settlement',  ('结算', 'sharehub-svc-finance')),
    ('invoice',     ('发票', 'sharehub-svc-finance')),
    ('report',      ('报表管理', 'sharehub-svc-report')),
    ('auth',        ('认证（B 端 / C 端）', 'sharehub-auth')),
    ('gateway',     ('设备对接服务', 'sharehub-svc-gateway')),
    ('api',         ('契约', 'sharehub-api')),
    ('common',      ('公共', 'sharehub-common')),
    ('app',         ('单体启动与装配', 'sharehub-app')),
])
# 单体启动模块里保留的顶层包（不进业务模块）
APP_KEEP = ('config', 'dto', 'integration', 'seed', 'portal')

# ── 接入层控制器 → 目标域（控制器随域，层由 URL 前缀决定：/api → api.b · /mp → api.c · /internal → api.internal）
PORTAL = {
    'AssetOpsController': ('device.asset', ''),
    'DeviceController': ('device.asset', ''),
    'DeviceOpsController': ('device.asset', ''),
    'DeviceSignalController': ('device.monitor', ''),
    'DeviceEventInternalController': ('device.monitor', ''),
    'InventoryController': ('device.inventory', ''),
    'OpsController': ('device.asset', '拆分：旧骨架控制器跨多个域，按端点归各域'),
    'MarketingController': ('sales.marketing', ''),
    'OrderOpsController': ('sales.order', ''),
    'PaymentChannelController': ('sales.payment', '通道配置随共用支付服务改为代理'),
    'PricingController': ('sales.price', ''),
    'RentController': ('sales.order', ''),
    'TradeController': ('sales.order', '拆分：旧骨架控制器跨多个域'),
    'TradeInternalController': ('sales.order', ''),
    'MpNearbyController': ('network.site', ''),
    'MpNearbyDtos': ('network.site', ''),
    'MpTradeDtos': ('sales.order', ''),
    'MpUserController': ('consumer.account', ''),
    'UserOpsController': ('consumer.account', ''),
    'UserController': ('consumer.account', '拆分：旧骨架控制器跨多个域'),
    'ConsumerAuthController': ('auth.consumer', ''),
    'MpFileController': ('system.file', ''),
    'FileController': ('system.file', ''),
    'NotifyController': ('system.notify', ''),
    'OrgController': ('system.iam', ''),
    'PlatformController': ('system.masterdata', ''),
    'SysDictController': ('system.masterdata', ''),
    'SysConfigController': ('system.setting', ''),
    'SysSettingController': ('system.setting', ''),
    'MpMetaController': ('system.setting', ''),
    'AgentApplyController': ('operator.apply', ''),
    'AgentController': ('operator.profile', ''),
    'AgentExtController': ('operator.profile', '拆分：账号 / 划拨 / 佣金各归其域'),
    'AgentExitController': ('operator.exit', ''),
    'ContractController': ('network.contract', ''),
    'SiteController': ('network.site', ''),
    'LocExtController': ('network.site', '拆分：线索 / 场地 / 点位各归其域'),
    'AlarmController': ('ops.alarm', ''),
    'AlarmTodoController': ('ops.alarm', ''),
    'CsController': ('ops.support', ''),
    'MpSupportController': ('ops.support', ''),
    'WoExtController': ('ops.workorder', ''),
    'AdjustmentController': ('finance.adjustment', ''),
    'FinanceController': ('finance', '拆分：分润 / 结算 / 提现 / 发票 / 账务 / 对账各归其域'),
    'StatementController': ('settlement.statement', ''),
    'ReportController': ('report.catalog', ''),
    'OperationController': ('report.dashboard', ''),
    'OpsFlowMetricsController': ('report.dashboard', ''),
    'VendorController': ('gateway.vendor', '端点迁入设备对接服务'),
    'InternalJobController': ('app.portal', '任务声明端点，属于单体装配'),
    'OpsKitIntrospectController': ('app.integration.opskit', ''),
    # 跨域装配件：同时读多个业务域，只能留在单体的跨域编排层（v4/09 §四 约束 1）
    'ConsumerOrderAssembler': ('app.portal.c', '跨域编排：订单 × 设备 × 网点'),
    'StoreCardAssembler': ('app.portal.c', '跨域编排：站点 × 机柜 × 报价'),
    'WorkOrderAssembler': ('app.portal.b', '跨域编排：工单 × 告警'),
    'OpsKitWebhookController': ('app.integration.opskit', ''),
}

# ── 归位规则：(现 Maven, 现包路径正则, 类名正则 | None, 要剥掉的现包前缀正则, 目标包, 备注)
# 第一条命中即生效。剥掉前缀后剩下的路径（entity / service/impl …）原样作为目标包的层级子包。
# 目标包写 '=' 表示不动。
RULES = [
    # 公共
    ('sharehub-common', r'^common(/|$)', None, r'^common', 'common', ''),
    ('sharehub-common', r'^audit$', None, r'^audit', 'common.audit', ''),
    ('sharehub-common', r'^identity$', None, r'^identity', 'common.identity', ''),
    ('sharehub-common', r'^svc$', None, r'^svc', 'common.internal', '服务间调用'),
    ('sharehub-common', r'^trace$', None, r'^trace', 'common.trace', ''),
    ('sharehub-common', r'^auth(/|$)', None, r'^auth', 'auth', '抽出 sharehub-auth'),
    # 契约
    ('sharehub-api', r'^api/(common|remote|gateway|ops)(/|$)', None, None, '=', ''),
    ('sharehub-api', r'^api/platform/flow$', None, r'^api/platform/flow', 'api.flow', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(Cabinet|DeviceOwnership|DeviceProtection|DeviceSignal)',
     r'^api/(core|platform)', 'api.device', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(CouponUse|MemberRights|Order|Refund|TradeAnomaly|TradeRemedy)',
     r'^api/(core|platform)', 'api.sales', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(Nickname|FreeRent)', r'^api/(core|platform)', 'api.consumer', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(Agent|DirectorySnapshot|AssetAssigned|AssignTargetType)',
     r'^api/(core|platform)', 'api.operator', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(Contract|Site|Location)', r'^api/(core|platform)', 'api.network', ''),
    ('sharehub-api', r'^api/(core|platform)/', r'^(Employee|File|Notify|PermCatalog|ProblemDirectory|SysParam)',
     r'^api/(core|platform)', 'api.system', ''),
    # 平台组：商家管理
    ('sharehub-svc-platform', r'^agent/apply(/|$)', None, r'^agent/apply', 'operator.apply', ''),
    ('sharehub-svc-platform', r'^agent(/|$)', r'Commission', r'^agent(/ext)?', 'settlement.profit',
     '代理佣金并入分润（与 share_rule 重复，见数据库设计 review）'),
    ('sharehub-svc-platform', r'^agent(/|$)', r'Account', r'^agent(/ext)?', 'operator.account', ''),
    ('sharehub-svc-platform', r'^agent(/|$)', r'(Assignment|OwnershipSync)', r'^agent(/ext)?', 'operator.assignment', ''),
    ('sharehub-svc-platform', r'^agent(/|$)', r'(Exit|OpsAssessment)', r'^agent(/ext)?', 'operator.exit', ''),
    ('sharehub-svc-platform', r'^agent(/|$)', r'Performance', r'^agent(/ext)?', 'operator.performance', ''),
    ('sharehub-svc-platform', r'^agent(/|$)', None, r'^agent(/ext)?', 'operator.profile', ''),
    # 平台组：网点管理
    ('sharehub-svc-platform', r'^loc(/|$)', r'Contract', r'^loc(/ext)?', 'network.contract', ''),
    ('sharehub-svc-platform', r'^loc(/|$)', r'Lead', r'^loc(/ext)?', 'network.lead', ''),
    ('sharehub-svc-platform', r'^loc(/|$)', r'Venue', r'^loc(/ext)?', 'network.venue', ''),
    ('sharehub-svc-platform', r'^loc(/|$)', r'(Location|SignalLevel)', r'^loc(/ext)?', 'network.location', ''),
    ('sharehub-svc-platform', r'^loc(/|$)', None, r'^loc(/ext)?', 'network.site', ''),
    # 平台组：系统管理（B 端登录与凭据池迁认证模块）
    ('sharehub-svc-platform', r'^platform/iam$', r'^AuthController$', r'^platform/iam', 'auth.staff', 'B 端登录'),
    ('sharehub-svc-platform', r'^platform/cred(/|$)', None, r'^platform/cred', 'auth.credential', '凭据池'),
    ('sharehub-svc-platform', r'^platform/(iam|org)(/|$)', r'^Audit', r'^platform/(iam|org)', 'system.audit', ''),
    ('sharehub-svc-platform', r'^platform/(iam|org)(/|$)', None, r'^platform/(iam|org)', 'system.iam', '合并 iam 与 org'),
    ('sharehub-svc-platform', r'^platform/sys(/|$)', r'(Openapi|OpenApi)', r'^platform/sys', 'system.openapi', ''),
    ('sharehub-svc-platform', r'^platform/sys(/|$)', None, r'^platform/sys', 'system.setting', ''),
    ('sharehub-svc-platform', r'^platform/md(/|$)', None, r'^platform/md', 'system.masterdata', ''),
    ('sharehub-svc-platform', r'^platform/notify(/|$)', None, r'^platform/notify', 'system.notify', ''),
    ('sharehub-svc-platform', r'^platform/file(/|$)', None, r'^platform/file', 'system.file', ''),
    # 交易组：设备管理（OTA 随设备对接服务）
    ('sharehub-svc-core', r'^dev(/|$)', r'(^|Dev)Ota', r'^dev', 'gateway.ota', 'OTA 移入设备对接服务（v4/09 §九）'),
    ('sharehub-svc-core', r'^dev(/|$)', r'(Signal|Monitor|DeviceLog|Shadow|^OnlineStatus|EventCode)', r'^dev',
     'device.monitor', ''),
    ('sharehub-svc-core', r'^dev(/|$)', None, r'^dev', 'device.asset', ''),
    ('sharehub-svc-core', r'^inv(/|$)', None, r'^inv', 'device.inventory', ''),
    # 交易组：销售管理
    ('sharehub-svc-core', r'^trade/pay(/|$)', r'^PaymentPort$', r'^trade/pay', 'api.sales.port',
     '支付端口移入契约（v4 §5.2）'),
    ('sharehub-svc-core', r'^trade/pay(/|$)', None, r'^trade/pay', 'sales.payment', '缩为支付编排'),
    ('sharehub-svc-core', r'^trade/price(/|$)', None, r'^trade/price', 'sales.price', ''),
    ('sharehub-svc-core', r'^trade(/|$)', None, r'^trade(/order)?', 'sales.order', ''),
    # 交易组：用户管理与营销（C 端登录迁认证模块；C 端开票迁发票）
    ('sharehub-svc-core', r'^user/consumer$', None, r'^user/consumer', 'auth.consumer', 'C 端登录'),
    ('sharehub-svc-core', r'^user(/|$)', r'Invoice', r'^user(/core)?', 'invoice.rental', 'C 端开票申请并入发票'),
    ('sharehub-svc-core', r'^user(/|$)', r'(^Mbr|Member)', r'^user(/(asset|member))?', 'sales.member', ''),
    ('sharehub-svc-core', r'^user/marketing(/|$)', None, r'^user/marketing', 'sales.marketing', ''),
    ('sharehub-svc-core', r'^user(/|$)', r'Coupon', r'^user(/member)?', 'sales.marketing', ''),
    ('sharehub-svc-core', r'^user/ad(/|$)', None, r'^user/ad', 'sales.ad', ''),
    ('sharehub-svc-core', r'^user/asset(/|$)', None, r'^user/asset', 'consumer.wallet', ''),
    ('sharehub-svc-core', r'^user(/|$)', None, r'^user(/(core|member))?', 'consumer.account', ''),
    # 运维组
    ('sharehub-svc-ops', r'^alarm(/|$)', None, r'^alarm', 'ops.alarm', ''),
    ('sharehub-svc-ops', r'^wo(/|$)', None, r'^wo(/ext)?', 'ops.workorder', '合并 wo.ext'),
    ('sharehub-svc-ops', r'^cs(/|$)', None, r'^cs', 'ops.support', ''),
    # 财务组：按表前缀拆平铺的 finance 包
    ('sharehub-svc-finance', r'^finance/statement$', None, r'^finance/statement', 'settlement.statement', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'(Acct|Ledger)', r'^finance', 'finance.ledger', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'Recon', r'^finance', 'finance.reconcile', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'Adjustment', r'^finance', 'finance.adjustment', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'Invoice', r'^finance', 'invoice.settle', ''),
    ('sharehub-svc-finance', r'^finance(/|$)',
     r'(Share|ReferFee|OrderSettledListener|ContractSignedListener|SiteClosedListener)', r'^finance',
     'settlement.profit', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'(Withdraw|Payout)', r'^finance', 'settlement.payout', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'(Settlement|Settle)', r'^finance', 'settlement.statement', ''),
    ('sharehub-svc-finance', r'^finance(/|$)', r'^(FinNos|FinDtos)$', r'^finance', 'finance',
     '三个财务模块共用，待按域拆'),
    # 设备对接服务
    ('sharehub-svc-gateway', r'^gw/driver(/|$)', None, r'^gw', 'gateway', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'Vendor', r'^gw', 'gateway.vendor', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'Command', r'^gw', 'gateway.command', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'Message', r'^gw', 'gateway.message', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'(Heartbeat|Telemetry)', r'^gw', 'gateway.telemetry', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'Binding', r'^gw', 'gateway.binding', ''),
    ('sharehub-svc-gateway', r'^gw(/|$)', r'^GwDtos$', r'^gw', 'gateway', '待按域拆'),
    ('sharehub-app-gateway', r'.*', None, None, '=', ''),
    # 单体：报表迁报表模块；接入层控制器随域；其余保留
    ('sharehub-app', r'^report(/|$)', None, r'^report', 'report.catalog', ''),
    ('sharehub-app', r'^operation(/|$)', None, r'^operation', 'report.dashboard', ''),
    ('sharehub-app', r'^portal/', 'PORTAL', r'^portal/\w+', None, ''),
    ('sharehub-app', r'^($|config|dto|integration|seed)', None, None, '=', ''),
]

MAPPING_RE = re.compile(r'@(?:Request|Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"(/[^"]*)"')
CONTROLLER_RE = re.compile(r'@RestController\b(?!Advice)')


def scan():
    out = []
    for mod in sorted(os.listdir(BACKEND)):
        base = os.path.join(BACKEND, mod, PKG_DIR)
        if not mod.startswith('sharehub-') or not os.path.isdir(base):
            continue
        for d, _, files in os.walk(base):
            for f in sorted(files):
                if not f.endswith('.java'):
                    continue
                rel = os.path.relpath(d, base).replace(os.sep, '/')
                rel = '' if rel == '.' else rel
                src = io.open(os.path.join(d, f), encoding='utf-8').read()
                out.append((mod, rel, f[:-5], src))
    return out


def layer_of(rest, src):
    """剥掉域前缀后的剩余路径 → 目标层级；控制器按 URL 前缀落 api.b / api.c / api.internal。"""
    if CONTROLLER_RE.search(src):
        m = MAPPING_RE.search(src)
        prefix = m.group(1) if m else '/api'
        if prefix.startswith('/mp'):
            return 'api.c'
        if prefix.startswith('/internal'):
            return 'api.internal'
        return 'api.b'
    parts = [p for p in rest.split('/') if p]
    if parts and parts[0] == 'internal':
        parts[0] = 'api.internal'
    return '.'.join(parts)


def target_of(mod, rel, cls, src):
    for rmod, rpath, rcls, strip, target, note in RULES:
        if rmod != mod or not re.search(rpath, rel):
            continue
        if rcls == 'PORTAL':
            if cls not in PORTAL:
                continue
            target, note = PORTAL[cls]
            rest = re.sub(strip, '', rel)
        elif rcls and not re.search(rcls, cls):
            continue
        else:
            rest = re.sub(strip, '', rel) if strip else rel
        if target == '=':
            pkg = rel.replace('/', '.')
            return (pkg, mod, note)
        lay = layer_of(rest, src)
        if target == 'app.portal':          # 单体跨域编排层只按端分：portal.b / portal.c / portal.internal
            lay = lay.replace('api.', '')
        pkg = target + ('.' + lay if lay else '')
        top = target.split('.')[0]
        if top == 'app':
            pkg = pkg[len('app.'):]
            return (pkg, 'sharehub-app', note)
        return (pkg, TOP[top][1], note)
    return None


def module_of(pkg, maven):
    top = pkg.split('.')[0] if pkg else ''
    if maven == 'sharehub-app' and (top in APP_KEEP or top == ''):
        return 'app'
    if maven == 'sharehub-app-gateway':
        return 'gateway'
    return top


IMPORT_RE = re.compile(r'^import\s+(?:static\s+)?ai\.neargo\.sharehub\.([\w.]+?)\.(\w+|\*)\s*;', re.M)
NEUTRAL = ('sharehub-api', 'sharehub-common', 'sharehub-auth', 'sharehub-app')


def main():
    rows, miss = [], []
    files = scan()
    first = {}
    pkg_maven = defaultdict(set)
    for mod, rel, cls, src in files:
        t = target_of(mod, rel, cls, src)
        if t:
            cur = rel.replace('/', '.')
            first[(cur, cls)] = t
            pkg_maven[cur].add(t[1])
    for mod, rel, cls, src in files:
        t = first.get((rel.replace('/', '.'), cls))
        if t is None:
            miss.append('%s/%s/%s' % (mod, rel, cls))
            continue
        pkg, tmaven, note = t
        # 接入层控制器：依赖跨到别的 Maven 模块的实现类 → 不能随域搬走，留在单体跨域编排层
        if mod == 'sharehub-app' and rel.startswith('portal') and CONTROLLER_RE.search(src) \
                and tmaven != 'sharehub-app':
            deps = set()
            for ipkg, icls in IMPORT_RE.findall(src):
                key = (ipkg, icls)
                mavens = {first[key][1]} if key in first else pkg_maven.get(ipkg, set())
                deps |= {m for m in mavens if m not in NEUTRAL}
            others = sorted(deps - {tmaven})
            if others:
                side = pkg.split('.api.')[-1] if '.api.' in pkg else 'b'
                note = ('跨域编排：还依赖 %s 的实现类，留在单体；改为只经契约 Port 后可随域迁入 `%s`'
                        % (' / '.join(m.replace('sharehub-', '') for m in others), pkg)
                        + ('；另需' + note if '拆分' in note else ''))
                pkg, tmaven = 'portal.' + side, 'sharehub-app'
        cur = rel.replace('/', '.')
        if tmaven != mod:
            act = '迁模块'
        elif pkg != cur:
            act = '改包'
        else:
            act = '不动'
        if '拆分' in note:
            act += '·拆分'
        rows.append(dict(cls=cls, mod=mod, cur=cur, pkg=pkg, tmaven=tmaven, act=act, note=note,
                         top=module_of(pkg, tmaven)))
    if miss:
        sys.stderr.write('以下 %d 个类没有被任何规则命中，请在 RULES / PORTAL 里补规则：\n  ' % len(miss))
        sys.stderr.write('\n  '.join(miss) + '\n')
        sys.exit(1)

    L = []
    L.append('# 服务端代码清单（现包 → 目标包）')
    L.append('')
    L.append('> **本文件由脚本生成，勿手改** —— `python3 backend/scripts/gen-code-inventory.py`。')
    L.append('> 规划与命名规则见手写文档 [服务端包名规划](./服务端包名规划.md)；归位规则写在脚本的 `RULES` / `PORTAL` 里。')
    L.append('> 包名一律省略公共前缀 `%s.`。动作：**不动** · **改包**（同一 Maven 模块内换包）· '
             '**迁模块**（换 Maven 模块）· **拆分**（一个类跨多个域，要拆成多个类）。' % BASE)
    L.append('')

    # 一、汇总
    by_top = defaultdict(list)
    for r in rows:
        by_top[r['top']].append(r)
    L.append('## 一、汇总')
    L.append('')
    L.append('| 业务模块 / 部件 | 目标顶层包 | 目标 Maven 模块 | 类数 | 不动 | 改包 | 迁模块 | 需拆分 |')
    L.append('|---|---|---|---:|---:|---:|---:|---:|')
    tot = [0, 0, 0, 0, 0]
    for top, (label, maven) in TOP.items():
        rs = by_top.get(top, [])
        if not rs:
            continue
        c = [len(rs),
             sum(1 for r in rs if r['act'].startswith('不动')),
             sum(1 for r in rs if r['act'].startswith('改包')),
             sum(1 for r in rs if r['act'].startswith('迁模块')),
             sum(1 for r in rs if '拆分' in r['act'])]
        tot = [a + b for a, b in zip(tot, c)]
        shown = '`%s`' % top if top != 'app' else '`config` `integration` `portal` `seed` …'
        L.append('| %s | %s | `%s` | %d | %d | %d | %d | %d |' % ((label, shown, maven) + tuple(c)))
    L.append('| **合计** | | | **%d** | **%d** | **%d** | **%d** | **%d** |' % tuple(tot))
    L.append('')

    # 现 Maven → 目标 Maven 流向
    flow = defaultdict(int)
    for r in rows:
        if r['mod'] != r['tmaven']:
            flow[(r['mod'], r['tmaven'])] += 1
    L.append('**跨 Maven 模块的迁移**（只列有变化的流向）：')
    L.append('')
    L.append('| 现 Maven 模块 | 目标 Maven 模块 | 类数 |')
    L.append('|---|---|---:|')
    for (a, b), n in sorted(flow.items(), key=lambda x: (-x[1], x[0])):
        L.append('| `%s` | `%s` | %d |' % (a, b, n))
    L.append('')

    # 二、需拆分
    split = [r for r in rows if '拆分' in r['act']]
    L.append('## 二、需要拆分的类')
    L.append('')
    L.append('一个类同时承载多个业务域的代码，搬家前要先拆。')
    L.append('')
    L.append('| 类 | 现位置 | 主目标包 | 说明 |')
    L.append('|---|---|---|---|')
    for r in sorted(split, key=lambda r: r['pkg']):
        L.append('| `%s` | `%s` · `%s` | `%s` | %s |' % (r['cls'], r['mod'], r['cur'] or '(根)', r['pkg'], r['note']))
    L.append('')

    # 三、留在单体跨域编排层
    keep = [r for r in rows if r['tmaven'] == 'sharehub-app' and r['pkg'].startswith('portal')]
    L.append('## 三、留在单体跨域编排层的类')
    L.append('')
    L.append('控制器随域搬走的前提是：它只依赖本域的实现类，其他域只经 `sharehub-api` 的契约 Port。'
             '下列类不满足（或本身就是跨域装配），先留在 `sharehub-app` 的 `portal.{b,c,internal}`。')
    L.append('')
    L.append('| 类 | 现包 | 目标包 | 原因 |')
    L.append('|---|---|---|---|')
    for r in sorted(keep, key=lambda r: (r['pkg'], r['cls'])):
        L.append('| `%s` | `%s` | `%s` | %s |' % (r['cls'], r['cur'], r['pkg'], r['note'] or '—'))
    L.append('')

    # 四、按目标包
    L.append('## 四、按目标包列出')
    L.append('')
    L.append('每个目标包下，按「现所在 Maven 模块 · 现包」分组列出类名。')
    L.append('')
    sec = 0
    for top, (label, maven) in TOP.items():
        rs = by_top.get(top, [])
        if not rs:
            continue
        sec += 1
        L.append('### 4.%d %s（`%s`）' % (sec, label, maven))
        L.append('')
        L.append('| 目标包 | 现位置 | 动作 | 类 |')
        L.append('|---|---|---|---|')
        grp = OrderedDict()
        for r in sorted(rs, key=lambda r: (r['pkg'], r['mod'], r['cur'], r['cls'])):
            grp.setdefault((r['pkg'], r['mod'], r['cur'], r['act'].split('·')[0]), []).append(r['cls'])
        for (pkg, mod, cur, act), clss in grp.items():
            where = '`%s` · `%s`' % (mod.replace('sharehub-', ''), cur or '(根)')
            L.append('| `%s` | %s | %s | %s |' % (pkg or '(根)', where, act, ' · '.join(clss)))
        L.append('')

    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L))
    print('✓ %s：%d 个类，%d 个目标包' % (os.path.relpath(OUT, ROOT), len(rows), len(set(r['pkg'] for r in rows))))


if __name__ == '__main__':
    main()
