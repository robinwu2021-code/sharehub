#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三语 key 对齐卡口：c-app 的 locale 文件 + 后端的 messages 包。

**为什么需要它**：两处都配了「静默回落」，于是漏一个 key 不报错、只是换个语言：

  · c-app  `createI18n({ fallbackLocale: "en" })`
      → 阿语缺 key **显示英文**。阿语用户看到一句英文，界面其余部分是阿语。
        没有报错、没有红字，只是那一句"不太对"。
  · 后端   `setFallbackToSystemLocale(false)`，缺省回落 messages.properties(zh)
      → 阿语/英语缺 key **显示中文**。这正是 2026-09-25 修掉的那一类
        （401/403 与认证报错写死中文）的另一种来法。

两处此前**都没有任何检查**。ops-web 有自己的 i18n.test.ts，c-app 连测试运行器都没有，
后端的 properties 更是纯文本 —— 谁手滑少写一行，要等有人切到那个语言才可能被发现。

用法：`python3 backend/scripts/i18n-parity.py`（退出码 0 = 通过）
挂在 ops-web 的 `npm run check:drift` 下。
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(ROOT)  # 仓库根


def ts_keys(path):
    """把嵌套对象字面量摊平成完整 key 路径。

    只认本仓库 locale 文件的写法（`key: {` 开子块、`key: "…"` 是叶子）——
    不是通用 TS 解析器。写法变了宁可这里报错，也不要悄悄少数几个 key。
    """
    out, stack = set(), []
    for raw in io.open(path, encoding='utf-8').read().split('\n'):
        t = raw.strip()
        if t.startswith('//') or not t:
            continue

        # 单行对象：`tabbar: { home: "附近", me: "我的" },`
        # 第一版只按行首 `key: {` 推栈、按 `}` 弹栈 —— 单行对象**只推不弹**，
        # 于是它之后的所有 key 都挂在错误的前缀下（实测报出 `tabbar.settings.deleteRevoke`）。
        # 两边同样错 ⇒ 数量仍然对得上，但报出来的名字是错的，
        # 而且两个不同的 key 有可能被折叠成同一条路径，把真差异盖掉。
        m = re.match(r'^(\w+):\s*\{(.*)\}\s*,?\s*$', t)
        if m:
            for k in re.findall(r'(\w+):\s*["\'`]', m.group(2)):
                out.add('.'.join(stack + [m.group(1), k]))
            continue

        m = re.match(r'^(\w+):\s*\{', t)
        if m:
            stack.append(m.group(1))
            continue
        if t.startswith('}'):
            if stack:
                stack.pop()
            continue
        m = re.match(r'^(\w+):\s*["\'`]', t)
        if m:
            out.add('.'.join(stack + [m.group(1)]))
    return out


def prop_keys(path):
    out = set()
    for raw in io.open(path, encoding='utf-8').read().split('\n'):
        t = raw.strip()
        if not t or t.startswith('#'):
            continue
        if '=' in t:
            out.add(t.split('=', 1)[0].strip())
    return out


def check(label, base_name, sets):
    """sets: {语言: key 集合}；以 base_name 那一份为基准。"""
    base = sets[base_name]
    bad = []
    for lang, ks in sets.items():
        if lang == base_name:
            continue
        miss = sorted(base - ks)
        extra = sorted(ks - base)
        if miss:
            bad.append('  %s 缺 %d 个：%s' % (lang, len(miss), ', '.join(miss[:8])
                                             + (' …' if len(miss) > 8 else '')))
        if extra:
            bad.append('  %s 多 %d 个（基准里没有，多半是改名漏了一边）：%s'
                       % (lang, len(extra), ', '.join(extra[:8]) + (' …' if len(extra) > 8 else '')))
    print('%-28s %s' % (label, ' · '.join('%s=%d' % (l, len(k)) for l, k in sets.items())))
    if bad:
        print('❌ %s 三语 key 不齐：' % label)
        print('\n'.join(bad))
    return not bad


def main():
    ok = True

    capp = os.path.join(ROOT, 'c-app/src/i18n/locale')
    if os.path.isdir(capp):
        ok &= check('c-app locale', 'zh',
                    {l: ts_keys(os.path.join(capp, l + '.ts')) for l in ('zh', 'en', 'ar')})

    msgs = os.path.join(ROOT, 'backend/sharehub-common/src/main/resources/i18n')
    ok &= check('backend messages', 'zh',
                {'zh': prop_keys(os.path.join(msgs, 'messages.properties')),
                 'en': prop_keys(os.path.join(msgs, 'messages_en.properties')),
                 'ar': prop_keys(os.path.join(msgs, 'messages_ar.properties'))})

    if ok:
        print('✅ 三语 key 对齐卡口通过。')
        return 0
    print('\n漏一个 key 不会报错 —— c-app 回落成英文、后端回落成中文，'
          '要等有人切到那个语言才可能被发现。补齐再提交。')
    return 1


if __name__ == '__main__':
    sys.exit(main())
