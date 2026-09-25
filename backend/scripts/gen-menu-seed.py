#!/usr/bin/env python3
"""
从 ops-web 的 nav.ts 生成 iam_menu 的行。

**为什么要有这个脚本**：菜单真源从 nav.ts 换成后端下发（2026-09-24 定），
而切换那一刻两边必须逐节点相等 —— 手写 127 行 SQL 做不到"相等"，
只能做到"看起来差不多"，而差的那几行不会有任何东西报错。

用法：
    cd ops-web && npx vitest run lib/__dump_nav.test.ts   # 先导出 nav.json
    python3 backend/scripts/gen-menu-seed.py nav.json > V__menu_seed.sql

导出那一步刻意不写进本脚本：nav.ts 是 TypeScript，靠正则去解析它
迟早会在某个换行或注释上悄悄读错一行，而读错的表现是"少一个菜单"。
让 TS 自己 import 出来，是唯一不会读错的方式。
"""
import json, sys

def q(v):
    if v is None: return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"

def jarr(v):
    # separators 必须去掉空格：卡口是拿这串与前端 JSON.stringify 的结果**逐字节**比的，
    # 默认的 ", " 会让每个数组都报成差异，于是真差异被淹在噪音里。
    return "NULL" if not v else q(json.dumps(v, ensure_ascii=False, separators=(",", ":")))

def main(path):
    nav = json.load(open(path, encoding="utf-8"))
    rows = []
    for si, s in enumerate(nav, 1):
        no = "M_" + s["key"]
        rows.append(dict(
            # type 两档：MENU（分组）/ ITEM（叶子）。词表由 V68 钉死在列注释上，
            # 并有 StoredValueInVocabularyTest 守着 —— 别再引入第三套命名。
            menu_no=no, parent_no=None, name=s["label"],
            name_en=s.get("nameEn"), name_ar=s.get("nameAr"), type="MENU",
            path=s.get("href"), icon=s.get("icon"), group_name=None, sort=si,
            # ready 是叶子级的解锁标记，对 section 无意义；列是 NOT NULL，发 0 不发 NULL
            perm=s.get("perm"), phase=s.get("phase", 1), ready=0,
            module=s.get("module"), modules=s.get("modules"),
            match_paths=s.get("match"), pin_bottom=1 if s.get("pinBottom") else 0,
            portal_for=s.get("portalFor"),
        ))
        for li, c in enumerate(s.get("children", []), 1):
            rows.append(dict(
                menu_no=f"{no}__{li}", parent_no=no, name=c["label"],
                name_en=c.get("nameEn"), name_ar=c.get("nameAr"), type="ITEM",
                path=c.get("href"), icon=None, group_name=c.get("group"), sort=li,
                perm=c.get("perm"), phase=c.get("phase", 1),
                ready=1 if c.get("ready") else 0,
                module=None, modules=None, match_paths=None, pin_bottom=0,
                portal_for=None,
            ))
    # name_en / name_ar 也进来（2026-09-25）：菜单的多语言此前另有一份
    # ops-web/lib/i18n/nav-labels.ts（172 条，**以中文标签做 key**）——
    # 于是在菜单管理里改个名字，它的翻译就静默失效、en/ar 回落成中文，而不报错。
    # 库里这两列定为真源，翻译跟着菜单走。
    cols = ["menu_no","parent_no","name","name_en","name_ar","type","path","icon","group_name","sort",
            "perm","phase","ready","module","modules","match_paths","pin_bottom","portal_for"]
    print("-- 本段由 backend/scripts/gen-menu-seed.py 从 ops-web/lib/nav.ts 生成，请勿手改。")
    print(f"-- 共 {len(rows)} 行（{len(nav)} 个 section + {len(rows)-len(nav)} 个叶子）。")
    print(f"INSERT INTO iam_menu ({', '.join(cols)}) VALUES")
    vals = []
    for r in rows:
        v = []
        for c in cols:
            x = r[c]
            if c in ("modules","match_paths","portal_for"): v.append(jarr(x))
            elif c in ("sort","phase","pin_bottom") or (c=="ready" and x is not None): v.append(str(x))
            elif x is None: v.append("NULL")
            else: v.append(q(x))
        vals.append("  (" + ", ".join(v) + ")")
    print(",\n".join(vals) + ";")

if __name__ == "__main__":
    main(sys.argv[1])
