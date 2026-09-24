#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成完整的接口参考文档（docs/api/reference.md）。

真值来源：`api-extract.py` 从控制器源码抽出的 `docs/api/contract.json`。
所以本文档**不可能与代码不一致** —— 它就是代码的另一种呈现。

判断（前缀怎么分、权限码怎么定、文档与代码冲突谁赢）留在手写的 README.md。

结构定义抽到文末统一列出、端点只引用名字：268 个端点里同一个 record 常被 5～10 个端点复用，
逐端点展开会让文档爆到上万行，而且改一个字段要改十处 —— 引用式只有一处。

用法：
    python3 backend/scripts/api-extract.py && python3 backend/scripts/gen-api-doc.py
"""
import io
import json
import os
import re
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONTRACT = os.path.join(ROOT, 'docs/api/contract.json')
OUT = os.path.join(ROOT, 'docs/api/reference.md')

# 前缀 → 分组标题。顺序即文档顺序：先运营端（按业务域），再 C 端，最后机器面。
GROUPS = OrderedDict([
    ('/api/auth', '登录与会话'),
    ('/api/ops', '运营：设备 · 场地 · 工单 · 告警 · 库存'),
    ('/api/trade', '交易：订单 · 计价 · 支付 · 结算 · 分润 · 发票'),
    ('/api/user', '用户：账号 · 钱包 · 券 · 会员 · 客服'),
    ('/api/agent', '代理商'),
    ('/api/platform', '平台：组织 · 权限 · 主数据 · 系统设置 · 通知'),
    ('/mp', 'C 端（`/mp`，消费者会话）'),
    ('/internal', '内部（`/internal`，服务间与批处理，不对外暴露）'),
    ('/gw', '设备网关南向'),
    ('/notify', '第三方回调（免鉴权，靠签名验真）'),
])

SIMPLE = re.compile(r'^(String|Integer|Long|Boolean|BigDecimal|Double|LocalDate|LocalDateTime|'
                    r'Object|Void|byte\[\]|int|long|boolean|double)$')


def group_of(path):
    for p, title in GROUPS.items():
        if path.startswith(p + '/') or path == p:
            return p
    return '其他'


def tidy(t):
    """Java 类型 → 文档里的读法。"""
    t = (t or '').replace('java.util.', '').replace('java.math.', '').replace('java.time.', '')
    t = re.sub(r'\bMap<String,\s*Object>', '对象（自由键）', t)
    t = re.sub(r'\bMap<String,\s*String>', '对象（字符串值）', t)
    return t


def main():
    data = json.load(io.open(CONTRACT, encoding='utf-8'))
    eps = data['endpoints']

    # ── 收集被引用的结构：出参元素、入参 body，以及它们内部嵌套的 record ──
    shapes = OrderedDict()

    def collect(name, fields):
        if not name or not fields or name in shapes:
            return
        shapes[name] = fields
        for f in fields:
            if f.get('fields'):
                inner = re.sub(r'^\w+<|>$', '', f['type']).split('.')[-1]
                collect(inner, f['fields'])

    for e in eps:
        if e.get('responseShape'):
            collect((e.get('returnElement') or '').split('.')[-1], e['responseShape'])
        if e.get('requestShape'):
            collect((e.get('bodyType') or '').split('.')[-1], e['requestShape'])

    def ref(t):
        """类型 → 若是已收录结构则给锚链接，否则原样。"""
        if not t:
            return '—'
        bare = re.sub(r'^(PageResult|List|Set|Optional|ResponseEntity|Result)<', '', t).rstrip('>')
        bare = bare.split('.')[-1]
        if bare in shapes:
            link = '[`%s`](#%s)' % (bare, bare.lower())
            wrap = t.split('<')[0] if '<' in t else ''
            if wrap == 'PageResult':
                return '分页<%s>' % link
            if wrap in ('List', 'Set'):
                return '数组<%s>' % link
            return link
        return '`%s`' % tidy(t)

    grouped = OrderedDict((p, []) for p in GROUPS)
    grouped['其他'] = []
    for e in eps:
        grouped[group_of(e['path'])].append(e)

    n_perm = sum(1 for e in eps if e['perm'])
    unprotected = [e for e in eps if not e['perm'] and e['path'].startswith('/api/')
                   and not e['path'].startswith('/api/auth')]

    L = []
    L.append('# 接口参考（全量 %d 个端点）\n' % len(eps))
    L.append('> **本文件由脚本生成，不要手改**：\n>')
    L.append('> ```bash\n> python3 backend/scripts/api-extract.py   # 从控制器源码抽真值\n'
             '> python3 backend/scripts/gen-api-doc.py    # 渲染本文件\n> ```\n>')
    L.append('> 所以它不可能与代码不一致 —— 它就是代码的另一种呈现。'
             '**接口该怎么设计**（前缀划分、权限码约定、文档与代码冲突谁赢）看 '
             '[README.md](./README.md)。\n')

    L.append('## 怎么读\n')
    L.append('| 约定 | 说明 |\n|---|---|')
    L.append('| 出参包装 | `ApiResponseWrapper` 把 **`ai.neargo.sharehub` 下所有控制器**的返回值'
             '（含 `/internal`、`/gw`，不只是 `/api`）包成 `{code, message, data}`，`code=0` 为成功；'
             '返回值已是 `Result` 的原样透传。**下文「出参」写的是 `data` 的形状**，不含这层壳 |')
    L.append('| 分页 | `分页<T>` = `{list: T[], total: number}`；入参统一 `page`（从 1 起）'
             '与 `size`（默认 10，上限 200，见 `AbstractCrudService`） |')
    L.append('| 时间 | 一律字符串 `yyyy-MM-dd HH:mm:ss`，不用时间戳（前端直接展示，不做时区换算） |')
    L.append('| 金额 | 一律带 2 位小数的数字 + 独立 `currency` 字段，**不用浮点做计算** |')
    L.append('| 权限 | 「权限码」列即 `@PreAuthorize("@perm.can(...)")`。'
             '缺权限码 → 403；未登录 → 401 |')
    L.append('| 受信头 | `X-Merchant-Id`（租户）/ `X-User-Id` / `X-Roles` 由边缘注入，'
             '**客户端同名头一律剥离**；业务代码只读不写 |')
    L.append('')

    L.append('## 覆盖情况\n')
    L.append('| | |\n|---|---|')
    L.append('| 端点 | **%d** |' % len(eps))
    L.append('| 带功能权限码 | %d |' % n_perm)
    L.append('| 有请求体 | %d |' % sum(1 for e in eps if e['bodyType']))
    L.append('| 数据结构 | %d 个（文末统一定义） |' % len(shapes))
    L.append('')

    if unprotected:
        L.append('### ⚠️ %d 个 `/api/**` 端点没有功能权限码\n' % len(unprotected))
        L.append('它们**不是匿名可读** —— `SecurityConfig` 对 `/api/**` 是 `anyRequest().authenticated()`，'
                 '未登录仍是 401。但没有 `@PreAuthorize` 意味着**任何已登录员工都能读**，'
                 '不分角色：客服能读账务分录、能读员工名册、能读审计日志。\n')
        L.append('这与「四层权限」的设计（认证 → 功能权限 → 数据范围 → 属主守卫）第二层缺失，'
                 '全部落在待拆分的旧骨架控制器上。\n')
        L.append('| 端点 | 落在 |\n|---|---|')
        for e in unprotected:
            L.append('| `%s %s` | `%s` |' % (e['verb'], e['path'], e['handler']))
        L.append('')

    L.append('---\n')
    for p, title in list(GROUPS.items()) + [('其他', '其他')]:
        items = grouped.get(p) or []
        if not items:
            continue
        L.append('\n## %s\n' % title)
        L.append('%d 个端点。\n' % len(items))
        for e in sorted(items, key=lambda x: (x['path'], x['verb'])):
            L.append('### `%s %s`\n' % (e['verb'], e['path']))
            if e['summary']:
                L.append('%s\n' % e['summary'])
            codes = e.get('perms') or ([e['perm']] if e['perm'] else [])
            # 多码端点写成「A 或 B」—— 只印第一个会让读文档的人以为访问面更窄
            bits = ['权限码 ' + ' 或 '.join('`%s`' % c for c in codes) if codes else '**无权限码**',
                    '`%s`' % e['handler']]
            L.append('%s\n' % ' · '.join(bits))

            ps = e['params']
            if ps:
                L.append('**入参**\n')
                L.append('| 位置 | 名 | 类型 | 必填 |\n|---|---|---|---|')
                for pa in ps:
                    where = {'path': '路径', 'query': '查询', 'header': '请求头'}.get(pa['in'], pa['in'])
                    L.append('| %s | `%s` | `%s` | %s |'
                             % (where, pa['name'], tidy(pa['type']), '是' if pa['required'] else '否'))
                L.append('')
            if e['bodyType']:
                L.append('**请求体** %s\n' % ref(e['bodyType']))
            L.append('**出参** %s\n' % ref(e['returnType']))

    L.append('\n---\n\n## 数据结构\n')
    L.append('共 %d 个。同一结构常被多个端点复用，故在此定义一次、上文引用。\n' % len(shapes))
    for name in sorted(shapes):
        L.append('### %s\n' % name)
        L.append('| 字段 | 类型 |\n|---|---|')
        for f in shapes[name]:
            L.append('| `%s` | %s |' % (f['name'], ref(f['type']) if f.get('fields')
                                        else '`%s`' % tidy(f['type'])))
        L.append('')

    if data.get('enums'):
        L.append('\n## 枚举\n')
        for k, v in sorted(data['enums'].items()):
            if v:
                L.append('- **%s** — `%s`' % (k, '` `'.join(v)))
        L.append('')

    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('写出 %s' % os.path.relpath(OUT, ROOT))
    print('  端点 %d · 结构 %d · 无权限码的 /api 端点 %d' % (len(eps), len(shapes), len(unprotected)))


if __name__ == '__main__':
    main()
