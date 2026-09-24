#!/usr/bin/env python3
"""
生成 iam_permission（权限码目录）的行。

**为什么要有这个脚本**：目录是角色勾选树与菜单挂码选择器的数据源。
2026-09-24 实测：后端强制 158 个码，而目录里只有 57 条 ——
**管理界面连 3/4 的权限都选不出来**，而这件事不会有任何症状：
管理员只会觉得「这个权限没做」。

根因：IamSeeder 灌目录时用的是 `RolePerms.MAP.values()`，
那是「内置角色持有的码」，不是「后端强制的码」。两者从来就不是一回事。

用法：
    python3 backend/scripts/gen-perm-catalog.py > /tmp/perm_catalog.sql

码从源码里扫 `@perm.can('...')` 得到，中文名读 perm-catalog-names.tsv。
**扫不到名字就报错退出** —— 宁可生成失败，也不要灌一批 code 当 name 的行进去，
那种行在勾选树上长得像乱码，而没人会回来补。
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
NAMES = ROOT / "scripts" / "perm-catalog-names.tsv"
PERM = re.compile(r"@perm\.can\('([^']+)'\)")


def enforced_codes():
    out = set()
    for p in ROOT.rglob("*.java"):
        if "target" in p.parts or "/test/" in str(p):
            continue
        out |= set(PERM.findall(p.read_text(encoding="utf-8")))
    return out


def names():
    d = {}
    for line in NAMES.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 3:
            d[parts[0]] = (parts[1], parts[2])
    return d


def q(v):
    return "'" + v.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    have = names()
    codes = enforced_codes() | set(have)
    unnamed = sorted(c for c in codes if c not in have)
    if unnamed:
        sys.exit("这些码没有中文名，先补进 perm-catalog-names.tsv：\n  " + "\n  ".join(unnamed))

    rows = sorted(codes)
    print("-- 本段由 backend/scripts/gen-perm-catalog.py 生成，请勿手改。")
    print(f"-- 共 {len(rows)} 条（后端强制 {len(enforced_codes())} · 仅目录 {len(rows) - len(enforced_codes())}）。")
    print("INSERT INTO iam_permission (code, name, module) VALUES")
    vals = [f"  ({q(c)}, {q(have[c][0])}, {q(have[c][1])})" for c in rows]
    # 已存在的只更新名字与模块：role_perm 按 code 关联，重灌会断掉现有授权
    print(",\n".join(vals))
    print("ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);")


if __name__ == "__main__":
    main()
