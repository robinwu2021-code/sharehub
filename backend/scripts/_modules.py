#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模块根解析 —— 所有扫描类脚本共用。

**为什么抽出来**：本会话已**五次**遇到「动了模块结构 → 脚本扫描根没跟上 → 卡口报绿但什么都没扫」。
每次都是逐个脚本手工补路径，补一次漏一次。改为**自动发现**：
凡 `backend/sharehub-*/src/main/java` 存在即纳入，新增模块无需改任何脚本。

这条经验的一般形式：**当同一类失误重复出现，要改的是机制而不是这一次的实例。**
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def source_roots(include_app=True):
    """全部 Java 源根。`include_app=False` 用于只关心 svc/api/common 的场景。"""
    base = os.path.join(ROOT, 'backend')
    out = []
    for name in sorted(os.listdir(base)):
        if not name.startswith('sharehub-'):
            continue
        if not include_app and name.startswith('sharehub-app'):
            continue
        p = os.path.join(base, name, 'src/main/java')
        if os.path.isdir(p):
            out.append(p)
    return out


def module_names():
    base = os.path.join(ROOT, 'backend')
    return [n for n in sorted(os.listdir(base))
            if n.startswith('sharehub-') and os.path.isdir(os.path.join(base, n, 'src'))]
