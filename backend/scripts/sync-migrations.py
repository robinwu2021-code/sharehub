# -*- coding: utf-8 -*-
"""docs/technical/ddl/*.sql  →  sharehub-app/src/main/resources/db/migration/V*.sql

为什么要转换而不是直接把 ddl/ 挂给 Flyway：
  1. ddl/ 里有 USE / CREATE DATABASE（pb_pii / pb_auth 是独立库），Flyway 绑定单库，
     切库会污染后续脚本；那两库交给 ops 脚本。
  2. Flyway 要求脚本可重复安全（幂等），而手写 DDL 里的 CREATE TABLE / ADD COLUMN / CHANGE COLUMN
     默认都不幂等。MariaDB 支持 IF NOT EXISTS / IF EXISTS，逐条加上。
  3. `AFTER 某列` 是纯装饰，却会让脚本依赖"那一列必须已存在" ——
     对预先存在的旧结构表会直接报 Unknown column（实测踩到）。一律剥掉。
  4. MariaDB 保留字（signal / interval / ...）做列名必须反引号。

用法：python3 backend/scripts/sync-migrations.py
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'docs', 'technical', 'ddl')
DST = os.path.join(ROOT, 'backend', 'sharehub-app', 'src', 'main', 'resources', 'db', 'migration')

# 顺序即版本序，必须与 ddl/README 的执行顺序一致
ORDER = [
    ("V1__loc_agt_iam.sql",        "pb_core-loc-agt-iam.sql"),
    ("V2__device_gateway.sql",     "pb_core-device-gateway.sql"),
    ("V3__trade_finance.sql",      "pb_core-trade-finance.sql"),
    ("V4__user_ad_workorder.sql",  "pb_core-user-ad-workorder.sql"),
    ("V5__v2_platform_system.sql", "pb_core-v2-platform-system.sql"),
    ("V6__v2_ops_alarm.sql",       "pb_core-v2-ops-alarm.sql"),
    ("V7__v2_trade_user.sql",      "pb_core-v2-trade-user.sql"),
    ("V8__v2_alter.sql",           "pb_core-v2-alter.sql"),
    ("V9__datascope_anchors.sql",  "pb_core-v2-datascope.sql"),
    ("V10__audit_columns.sql",     "pb_core-v3-audit-columns.sql"),
]

# MariaDB 保留字，用作列名必须反引号
RESERVED = {"signal", "interval", "rows", "key", "order", "group", "range", "lead", "over"}


def convert(text, src_name):
    lines, skipping = [], False
    for line in text.split("\n"):
        st = line.strip()
        if re.match(r'^(USE|CREATE DATABASE)\b', st, re.I):
            lines.append("-- [sync] 移除切库语句（Flyway 绑定 pb_core）：" + st)
            if re.match(r'^CREATE DATABASE IF NOT EXISTS pb_(pii|auth)', st, re.I):
                skipping = True
            elif re.match(r'^USE pb_core', st, re.I):
                skipping = False
            continue
        if skipping:
            continue
        lines.append(line)
    s = "\n".join(lines)

    # 幂等化
    s = re.sub(r'\bCREATE TABLE (?!IF NOT EXISTS)', 'CREATE TABLE IF NOT EXISTS ', s)
    s = re.sub(r'\bADD COLUMN (?!IF NOT EXISTS)', 'ADD COLUMN IF NOT EXISTS ', s)
    s = re.sub(r'\bADD (UNIQUE )?KEY (?!IF NOT EXISTS)', lambda m: 'ADD %sKEY IF NOT EXISTS ' % (m.group(1) or ''), s)
    s = re.sub(r'\bCHANGE COLUMN (?!IF EXISTS)', 'CHANGE COLUMN IF EXISTS ', s)
    s = re.sub(r'\bDROP COLUMN (?!IF EXISTS)', 'DROP COLUMN IF EXISTS ', s)

    # 种子 INSERT 必须幂等：Flyway 正常只跑一次，但开发期重置历史/repair 后会重跑，
    # 裸 INSERT 会撞唯一键（实测 V1 的内置角色种子踩到 Duplicate entry 'R1'）。
    # 这里只做检查告警，不自动改写 —— 改写 SQL 语义风险太高，应在源文件里显式写 ON DUPLICATE KEY。
    for m in re.finditer(r'(?m)^INSERT INTO (\w+)', s):
        tail = s[m.start():m.start() + 2000]
        if 'ON DUPLICATE KEY' not in tail.split(';')[0] and 'INSERT IGNORE' not in tail.split(';')[0]:
            sys.stderr.write("  ⚠️ %s 的 INSERT INTO %s 非幂等，重跑会撞唯一键 —— 请在源文件加 ON DUPLICATE KEY\n"
                             % (src_name, m.group(1)))

    # AFTER 子句：纯装饰，却让脚本依赖某列已存在 → 剥掉
    s = re.sub(r'\s+AFTER\s+`?\w+`?', '', s)

    # 保留字列名加反引号（仅列定义位置：行首缩进后的标识符，或 ADD COLUMN IF NOT EXISTS 之后）
    for w in RESERVED:
        s = re.sub(r'(ADD COLUMN IF NOT EXISTS\s+)(%s)(\s)' % w, r'\1`\2`\3', s, flags=re.I)
        s = re.sub(r'(?m)^(\s{2,})(%s)(\s+[A-Z])' % w, r'\1`\2`\3', s)

    hdr = ("-- 自动生成，勿手改：源文件 docs/technical/ddl/%s\n"
           "-- 重新同步：python3 backend/scripts/sync-migrations.py\n"
           "-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·\n"
           "--            保留字列名加反引号\n\n" % src_name)
    return hdr + s


def main():
    if not os.path.isdir(DST):
        os.makedirs(DST)
    for dst, src in ORDER:
        p = os.path.join(SRC, src)
        if not os.path.exists(p):
            print("  ⚠️ 源缺失，跳过: %s" % src); continue
        io.open(os.path.join(DST, dst), 'w', encoding='utf-8').write(
            convert(io.open(p, encoding='utf-8').read(), src))
        print("  %-30s ← %s" % (dst, src))
    print("\n同步 %d 个迁移脚本（V11 sys_token 与 V12+ 为手写，不受本脚本影响）" % len(ORDER))


if __name__ == '__main__':
    main()
