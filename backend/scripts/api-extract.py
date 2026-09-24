#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从控制器源码抽取端点契约（路径 / 权限码 / 入参 / 出参形状）。

为什么抽而不是手写：268 个端点的输入输出，手写文档在下一次改代码时就过期，
而「过期的接口文档」比没有文档更危险 —— 前端会照着它写然后对不上。
本脚本每次重跑都从代码取真值，文档里的判断部分（分组、规则、冲突裁决）留在手写的 README。

用法：
    python3 backend/scripts/api-extract.py            # 写出 /tmp/api_contract.json
    python3 backend/scripts/api-extract.py --md       # 顺带生成 markdown 到 stdout

解析靠正则而非完整 Java 语法树：本工程控制器风格统一（一律注解式路由、
record 承载 DTO、无动态注册），正则够用且没有额外依赖。**不通用，只服务本仓库**。
"""
import io
import json
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# 模块根**自动发现**，不写死路径 —— 本会话五次栽在「动了模块结构、扫描根没跟上」上，
# 补一次漏一次。见 _modules.py 的说明。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _modules import source_roots  # noqa: E402
SRC_ROOTS = source_roots()

VERBS = {'GetMapping': 'GET', 'PostMapping': 'POST', 'PutMapping': 'PUT',
         'DeleteMapping': 'DELETE', 'PatchMapping': 'PATCH'}


# ─────────────────────────── 通用小工具 ───────────────────────────

def strip_comments(s, want_docs=False):
    """剥掉块注释与行注释，但保留字符串字面量里的内容。

    `want_docs=True` 时额外返回 {剥离后下标: javadoc 正文}。**必须与剥离在同一次遍历里产出** ——
    首版分两次走（javadoc 位置在原文上算、代码在剥离后的串上算），只跳块注释没跳行注释，
    两边下标每遇一个 `//` 就错开一截，结果整份文档的方法注释集体挂到了下一个端点上
    （`/menus` 的注释跑到 `/permissions`，没注释的 `/me` 反而捡到别人的）。
    同一次遍历就不存在「两套下标要保持一致」这回事。

    行注释也必须剥：本工程习惯在注解之间写 `//` 说明（如 IamAdminController 在
    `@PutMapping` 与 `@PreAuthorize` 之间解释权限码收敛的理由），只剥块注释会让注解扫描停在那里，
    该端点的权限码就丢了。反过来不能用朴素的 `//.*$` —— 字符串里的 `http://` 会被腰斩。
    """
    out, docs, i, n, olen = [], {}, 0, len(s), 0
    while i < n:
        ch = s[i]
        if ch in '"\'':                       # 字符串：整段原样搬运
            q, j = ch, i + 1
            while j < n:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == q:
                    j += 1
                    break
                j += 1
            out.append(s[i:j])
            olen += j - i
            i = j
        elif s.startswith('/*', i):
            end = s.find('*/', i)
            end = n if end < 0 else end + 2
            if s.startswith('/**', i):
                docs[olen + 1] = s[i + 3:end - 2]   # 键 = 注释在剥离后串里的落点
            i = end
            out.append(' ')
            olen += 1
        elif s.startswith('//', i):
            end = s.find('\n', i)
            i = n if end < 0 else end
            out.append(' ')
            olen += 1
        else:
            out.append(ch)
            olen += 1
            i += 1
    stripped = ''.join(out)
    return (stripped, docs) if want_docs else stripped


def split_top_level(s, sep=','):
    """按顶层分隔符切分，忽略括号/尖括号内的分隔符。

    泛型和嵌套调用里全是逗号 —— `Map<String,Object> m, List<X> y` 直接 split(',') 会切错。
    本会话已两次栽在「括号内的逗号」上（DECIMAL(18,2) 被截断），这里一次性做对。
    """
    out, buf, depth = [], [], 0
    for ch in s:
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        if ch == sep and depth == 0:
            out.append(''.join(buf).strip())
            buf = []
        else:
            buf.append(ch)
    if ''.join(buf).strip():
        out.append(''.join(buf).strip())
    return out


def read_balanced(s, i):
    """从 s[i]=='(' 起读到配对的 ')'，返回 (内容, 右括号后的下标)。

    必须自己配对而不能用 `\\([^)]*\\)`：`@PreAuthorize("@perm.can('x:y:z')")` 的括号是嵌套的，
    非配对正则会在里层 `)` 截断 —— 首版就栽在这，268 个端点只解析出 61 个、权限码一个没抓到。
    同时要跳过字符串字面量，否则路径里的括号会把深度算歪。
    """
    depth, j, in_str, quote = 0, i, False, ''
    while j < len(s):
        ch = s[j]
        if in_str:
            if ch == '\\':
                j += 2
                continue
            if ch == quote:
                in_str = False
        elif ch in '"\'':
            in_str, quote = True, ch
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
        j += 1
    return '', len(s)


def read_annotations(s, i):
    """从 i 开始连续读注解，返回 ({名: 参数}, 下一个非注解字符的下标)。"""
    ann = {}
    while True:
        while i < len(s) and s[i] in ' \t\r\n':
            i += 1
        if i >= len(s) or s[i] != '@':
            return ann, i
        m = re.match(r'@(\w+)', s[i:])
        if not m:
            return ann, i
        name = m.group(1)
        i += m.end()
        while i < len(s) and s[i] in ' \t':
            i += 1
        args = ''
        if i < len(s) and s[i] == '(':
            args, i = read_balanced(s, i)
        ann[name] = args


def first_sentence(javadoc):
    """取 javadoc 第一句作为端点摘要。"""
    if not javadoc:
        return ''
    t = re.sub(r'^\s*\*\s?', '', javadoc, flags=re.M)
    t = re.sub(r'\{@code\s+([^}]*)\}', r'`\1`', t)
    t = re.sub(r'\{@link\s+([^}]*)\}', r'`\1`', t)
    t = re.sub(r'<[^>]+>', '', t)
    t = re.sub(r'@\w+.*$', '', t, flags=re.S)          # 砍掉 @param/@return 块
    t = ' '.join(t.split())
    m = re.match(r'(.+?)(?:。|\. )', t)
    return (m.group(1) + '。') if m else t[:120]


# ─────────────────────────── 类型字典：record / enum ───────────────────────────

def scan_types():
    """全仓扫 record 与 enum，供出入参形状展开。

    record 可嵌套在类里（`FinDtos.ShareRecord`），故同时登记短名与「外层.短名」。
    """
    records, enums = {}, {}
    for _root in SRC_ROOTS:
      for base, _, files in os.walk(_root):
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(base, f)
            raw = io.open(p, encoding='utf-8').read()
            src = strip_comments(raw)
            outer = os.path.splitext(f)[0]

            for m in re.finditer(r'\brecord\s+(\w+)\s*\(([^{;]*?)\)\s*(?:implements[^{]*)?\{', src, re.S):
                name, body = m.group(1), m.group(2)
                fields = []
                for part in split_top_level(body):
                    part = re.sub(r'@\w+(\([^)]*\))?\s*', '', part).strip()
                    if not part:
                        continue
                    bits = part.rsplit(' ', 1)
                    if len(bits) == 2:
                        fields.append({'name': bits[1].strip(), 'type': bits[0].strip()})
                records[name] = fields
                records['%s.%s' % (outer, name)] = fields

            for m in re.finditer(r'\benum\s+(\w+)\s*\{([^}]*)\}', src, re.S):
                vals = [v.strip().split('(')[0] for v in m.group(2).split(',')]
                enums[m.group(1)] = [v for v in vals if re.match(r'^[A-Z][A-Z0-9_]*$', v)]
    return records, enums


# ─────────────────────────── 控制器解析 ───────────────────────────

def parse_controller(path, records):
    raw = io.open(path, encoding='utf-8').read()
    src, docs = strip_comments(raw, want_docs=True)

    cls_prefix = ''
    m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', src.split('class ')[0])
    if m:
        cls_prefix = m.group(1)

    # 注释归属：一段 javadoc 只归**它后面第一个**路由注解，且认领后不再给别人。
    #
    # 反过来（每个端点往前找最近的注释）会让没写注释的方法捡到上一个方法的注释 ——
    # 实测 AuthController 的 `/me` 与 `/logout` 都没注释，却双双捡到 `/permissions` 的，
    # 生成的文档看起来字段齐全，其实在说谎。宁可留空也不能挂错。
    map_pos = [m.start() for m in re.finditer(r'@(' + '|'.join(VERBS) + r')\b', src)]
    owner = {}
    for at in sorted(docs):
        nxt = next((p for p in map_pos if p >= at), None)
        if nxt is not None and nxt - at < 240 and nxt not in owner:
            owner[nxt] = docs[at]

    def javadoc_before(pos):
        return owner.get(pos, '')

    endpoints = []
    for am in re.finditer(r'@(' + '|'.join(VERBS) + r')\b', src):
        start = am.start()
        ann, i = read_annotations(src, start)
        verb = VERBS[am.group(1)]

        # **取全部路径，不是第一个** —— @PostMapping({"", "/{agentNo}"}) 这种多值映射
        # 一个注解映射两个路径。2026-09-23 之前这里用 re.search 只取第一个，
        # 多值映射的第二个路径被静默丢掉：contract.json 里只有 POST /api/agent/agents，
        # 没有 /{agentNo}。后果是 api-align.py 把前端对编辑接口的调用报成
        # **「前端在调、后端没有」的运行期 404**，而后端明明有 —— 一次凭空的假警报，
        # 且方向最误导：它指着能用的功能说它坏了。
        # 全 app 有 4 处这种写法（agents / cabinets / sites / locations 的新建+编辑合一）。
        subs = re.findall(r'"([^"]*)"', ann.get(am.group(1), '') or '') or ['']

        # 一个 @PreAuthorize 里可能挂**多个**码：
        #   @PreAuthorize("@perm.can('a') or @perm.can('b')")
        # 只取第一个的话，契约会少记一半 —— 而 contract.json 正是五个对齐检查器
        # 与 API 文档的输入。实测 ReportController 的三码端点就一直只记着第一个，
        # 于是那个端点在文档和卡口眼里的可访问面是错的，且没有任何症状。
        #
        # `perm` 保持「第一个码」不变（老的消费者不受影响），
        # 另出 `perms` 给需要判断「到底哪些码放行」的检查器用。
        perm = None
        perms = []
        pre = ann.get('PreAuthorize')
        if pre:
            perms = re.findall(r"'([^']+)'", pre)
            perm = perms[0] if perms else pre.strip().strip('"')
            if not perms:
                perms = [perm]

        # 注解读完，i 指向签名开头：[修饰符] 返回类型 方法名(
        sig = src[i:src.find('(', i)] if src.find('(', i) > 0 else ''
        sig_bits = ' '.join(sig.split()).split(' ')
        if len(sig_bits) < 2:
            continue
        mname = sig_bits[-1]
        ret = ' '.join(b for b in sig_bits[:-1] if b not in
                       ('public', 'protected', 'private', 'final', 'static'))
        args_raw, _ = read_balanced(src, src.find('(', i))

        jd = javadoc_before(start)

        params, body_type = [], None
        for arg in split_top_level(args_raw):
            if not arg.strip():
                continue
            kind = None
            required = True
            if '@RequestBody' in arg:
                kind = 'body'
            elif '@PathVariable' in arg:
                kind = 'path'
            elif '@RequestParam' in arg:
                kind = 'query'
                required = 'required = false' not in arg and 'required=false' not in arg
            elif '@RequestHeader' in arg:
                kind = 'header'
                required = 'required = false' not in arg and 'required=false' not in arg
            else:
                continue
            bare = re.sub(r'@\w+(\([^)]*\))?\s*', '', arg).strip()
            bits = bare.rsplit(' ', 1)
            if len(bits) != 2:
                continue
            typ, nm = bits[0].strip(), bits[1].strip()
            if kind == 'body':
                body_type = typ
            else:
                params.append({'in': kind, 'name': nm, 'type': typ, 'required': required})

        for sub in subs:
            full = (cls_prefix + sub) or cls_prefix or '/'
            full = re.sub(r'//+', '/', full)
            endpoints.append(OrderedDict([
                ('verb', verb), ('path', full), ('perm', perm), ('perms', perms),
                ('handler', '%s#%s' % (os.path.basename(path)[:-5], mname)),
                ('summary', first_sentence(jd)),
                ('params', params), ('bodyType', body_type),
                ('returnType', ' '.join(ret.split())),
            ]))
    return endpoints


# ─────────────────────────── 形状展开 ───────────────────────────

def unwrap(t):
    """剥掉 PageResult<> / List<> / Result<> 等包装，返回 (容器, 元素类型)。"""
    if not t:
        return None, None
    t = t.strip()
    m = re.match(r'^(PageResult|List|Set|Optional|ResponseEntity|Result)\s*<\s*(.+)\s*>$', t)
    if m:
        inner_c, inner_t = unwrap(m.group(2))
        return (m.group(1) if inner_c is None else '%s<%s>' % (m.group(1), inner_c)), inner_t
    return None, t


def shape_of(t, records, depth=0):
    """把类型展开成字段列表；不是 record 的（Map/基本类型/实体）返回 None。"""
    if not t or depth > 2:
        return None
    _, elem = unwrap(t)
    if not elem:
        return None
    elem = elem.split('.')[-1] if elem not in records else elem
    fields = records.get(elem)
    if not fields:
        return None
    out = []
    for f in fields:
        row = {'name': f['name'], 'type': f['type']}
        nested = shape_of(f['type'], records, depth + 1)
        if nested:
            row['fields'] = nested
        out.append(row)
    return out


# ─────────────────────────── 主流程 ───────────────────────────

def main():
    records, enums = scan_types()
    all_eps = []
    for _root in SRC_ROOTS:
      for base, _, files in os.walk(_root):
        for f in files:
            if f.endswith('Controller.java'):
                all_eps.extend(parse_controller(os.path.join(base, f), records))

    for e in all_eps:
        container, elem = unwrap(e['returnType'])
        e['returnContainer'] = container
        e['returnElement'] = elem
        e['responseShape'] = shape_of(e['returnType'], records)
        e['requestShape'] = shape_of(e['bodyType'], records) if e['bodyType'] else None

    all_eps.sort(key=lambda x: (x['path'], x['verb']))

    dup = OrderedDict()
    for e in all_eps:
        dup.setdefault((e['verb'], e['path']), []).append(e['handler'])
    conflicts = {'%s %s' % k: v for k, v in dup.items() if len(v) > 1}

    out = {'endpoints': all_eps, 'enums': enums, 'conflicts': conflicts,
           'stats': {'endpoints': len(all_eps), 'records': len(set(records)) // 2,
                     'withPerm': sum(1 for e in all_eps if e['perm']),
                     'withBody': sum(1 for e in all_eps if e['bodyType']),
                     'shapedResponses': sum(1 for e in all_eps if e['responseShape'])}}
    # 提交进仓库而不是丢 /tmp：文档生成器与 ops-web 的 parity 门禁都读它，
    # 两个消费者必须看同一份真值 —— 否则「后端有多少端点」会出现两个互相矛盾的答案
    # （实测过：ops-web 那套自己的正则数出 267，本脚本 268）。
    for dest in (os.path.join(ROOT, 'docs/api/contract.json'), '/tmp/api_contract.json'):
        io.open(dest, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=1))

    s = out['stats']
    print('端点 %d（带权限码 %d · 有请求体 %d · 出参形状可展开 %d）'
          % (s['endpoints'], s['withPerm'], s['withBody'], s['shapedResponses']))
    print('record 类型 %d · enum %d' % (s['records'], len(enums)))
    if conflicts:
        print('\n⚠️ 重复映射 %d 处（Spring 启动会失败，说明解析有误或代码真有问题）：' % len(conflicts))
        for k, v in list(conflicts.items())[:10]:
            print('   %-46s %s' % (k, ', '.join(v)))
    prefixes = OrderedDict()
    for e in all_eps:
        p = '/'.join(e['path'].split('/')[:3])
        prefixes[p] = prefixes.get(p, 0) + 1
    print('\n按前缀：')
    for p, c in sorted(prefixes.items(), key=lambda kv: -kv[1]):
        print('   %-28s %3d' % (p, c))


if __name__ == '__main__':
    main()
