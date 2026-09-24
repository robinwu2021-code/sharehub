#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成完整的库表参考文档（docs/technical/db-schema-reference.md）。

真值来源（**分工是刻意的**）：
  · 结构（表/列/类型/可空/索引）—— 取自实际库 information_schema。
    不取 DDL 文件：DDL 用 `IF NOT EXISTS` 落到既有表上是空操作，文件写了不代表库里有。
  · 注释 —— 库里只有 43% 的列带注释（同上，既有表没被 `IF NOT EXISTS` 补上），
    故库里为空时回落到 DDL 文件里的同表同名列注释，并在文档里标注来源。
  · 实体映射 —— 扫 `@TableName`，标出每张表对应哪个 Java 实体（没有实体的表要么是关联表要么是漏建）。

设计判断（为什么表这么设计）留在手写的 db-design.md，本文件只回答「现在到底是什么」。

用法：python3 backend/scripts/gen-db-doc.py
"""
import io
import os
import re
import subprocess
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DDL_DIR = os.path.join(ROOT, 'docs/technical/ddl')
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _modules import source_roots  # noqa: E402
SRC_ROOTS = source_roots()
OUT = os.path.join(ROOT, 'docs/technical/db-schema-reference.md')
DB = os.environ.get('SHAREHUB_DOC_DB', 'pb_core')
MIGRATION_DIR = os.path.join(ROOT, 'backend/sharehub-app/src/main/resources/db/migration')
# 显式指定字符集：不指定就取决于跑脚本那台机器的 locale，
# 而「文档在某台机器上生成会带乱码」是查不出来的那种问题。
MYSQL = ['mysql', '--default-character-set=utf8mb4',
         '-upowerbank', '-ppowerbank', '-N', '-B', DB, '-e']


def q(sql):
    r = subprocess.run(MYSQL + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit('查库失败：%s' % r.stderr.strip())
    return [line.split('\t') for line in r.stdout.strip('\n').split('\n') if line]


def assert_db_is_current():
    """库落后于迁移文件就**拒绝生成**，不是警告一句继续写。

    本文件回答的是「现在到底是什么」，读的人拿它当真源。
    从一个落后 N 个迁移的库生成出来的文档**看起来完全正常** ——
    没有报错、没有空洞，只是少了那 N 个迁移带来的表与列，
    而少了什么恰恰是看不出来的。写出去比不写更糟。

    实测：2026-09-24 本机 pb_core 停在 V50，而迁移文件已到 V68 —— 差 18 个。
    那次若照常生成，会把这段时间所有的新表新列从文档里抹掉。
    """
    have = {int(r[0]) for r in q("SELECT version FROM flyway_schema_history "
                                 "WHERE version IS NOT NULL AND version REGEXP '^[0-9]+$'")
            if r and r[0]}
    want = set()
    for f in os.listdir(MIGRATION_DIR):
        m = re.match(r'V(\d+)(?:_\d+)?__', f)
        if m:
            want.add(int(m.group(1)))
    missing = sorted(want - have)
    if missing:
        raise SystemExit(
            '拒绝生成：库 `%s` 落后于迁移文件 %d 个版本 —— 缺 V%s\n'
            '  从落后的库生成的文档看起来完全正常，只是悄悄少了那些表与列。\n'
            '  先把迁移跑到这个库上（起一次应用，或 flyway migrate），或用\n'
            '  SHAREHUB_DOC_DB=<已跑完迁移的库> python3 backend/scripts/gen-db-doc.py'
            % (DB, len(missing), ', V'.join(str(v) for v in missing[:8])
               + ('…' if len(missing) > 8 else '')))


# 双重编码的签名：UTF-8 中文被当成 latin1 再编码一次之后，落在这一段里
MOJIBAKE = re.compile('[\u00c0-\u00ff][\u0080-\u00bf\u2000-\u203a]')


def assert_comments_readable(rows):
    """注释读出来是乱码就**拒绝生成**。

    乱码可能来自两处：库里存的就是双重编码（本机 pb_core 的 agt_apply 就是），
    或客户端字符集不对。两种都不该被写进文档 ——
    **一份带乱码的参考文档，下一个人会照着它把乱码抄进代码注释里。**
    """
    bad = sorted({r[0] for r in rows if len(r) > 1 and r[1] and MOJIBAKE.search(r[1])})
    if bad:
        raise SystemExit(
            '拒绝生成：%d 张表/列的注释读出来是乱码（双重编码），例如 %s\n'
            '  先确认库里存的是什么：\n'
            '    mysql --default-character-set=utf8mb4 -N -B %s -e "SELECT table_name, table_comment '
            'FROM information_schema.tables WHERE table_schema=DATABASE()"\n'
            '  库里就是坏的 → 重建那张表的注释；库里是好的 → 是客户端字符集问题，本脚本已显式指定 utf8mb4。'
            % (len(bad), '、'.join(bad[:5]), DB))


def ddl_comments():
    """从 DDL 文件里补捞列注释，键为 (表, 列)。"""
    out = {}
    if not os.path.isdir(DDL_DIR):
        return out
    for f in sorted(os.listdir(DDL_DIR)):
        if not f.endswith('.sql'):
            continue
        s = io.open(os.path.join(DDL_DIR, f), encoding='utf-8').read()
        # CREATE TABLE 块
        for m in re.finditer(r'CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?\s*\((.*?)\n\)', s, re.S | re.I):
            tbl, body = m.group(1), m.group(2)
            for lm in re.finditer(r"^\s+`?(\w+)`?\s+[^,]*?COMMENT\s+'([^']*)'", body, re.M):
                out.setdefault((tbl, lm.group(1)), lm.group(2))
        # ALTER ... ADD COLUMN
        for m in re.finditer(r'ALTER TABLE\s+`?(\w+)`?(.*?);', s, re.S | re.I):
            tbl, body = m.group(1), m.group(2)
            for lm in re.finditer(r"ADD COLUMN(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?[^,;]*?COMMENT\s+'([^']*)'", body, re.I):
                out.setdefault((tbl, lm.group(1)), lm.group(2))
    return out


def entity_map():
    """表名 → Java 实体类名（按 @TableName 逐类扫，与 entity-column-diff.py 同口径）。"""
    out = {}
    for _root in SRC_ROOTS:
      for base, _, files in os.walk(_root):
        for f in files:
            if not f.endswith('.java'):
                continue
            s = io.open(os.path.join(base, f), encoding='utf-8').read()
            for m in re.finditer(r'@TableName\s*\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                                 r'(?:public\s+)?(?:final\s+)?class\s+(\w+)', s):
                tm = re.search(r'"([^"]+)"', m.group(1))
                if tm:
                    out.setdefault(tm.group(1), []).append(m.group(2))
    return out


def main():
    # 两道闸在最前面：宁可什么都不写，也不写一份看起来正常的错文档
    assert_db_is_current()
    tables = q("SELECT table_name, IFNULL(table_comment,''), IFNULL(table_rows,0) "
               "FROM information_schema.tables WHERE table_schema='%s' ORDER BY table_name" % DB)
    cols = q("SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_default,'—'), "
             "column_key, IFNULL(column_comment,''), extra "
             "FROM information_schema.columns WHERE table_schema='%s' "
             "ORDER BY table_name, ordinal_position" % DB)
    assert_comments_readable(tables)                       # 表注释：(表名, 注释, 行数)
    assert_comments_readable([(r[0] + '.' + r[1], r[6]) for r in cols if len(r) > 6])
    idx = q("SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index), non_unique "
            "FROM information_schema.statistics WHERE table_schema='%s' "
            "GROUP BY table_name, index_name, non_unique ORDER BY table_name, index_name" % DB)

    dc = ddl_comments()
    em = entity_map()

    by_col, by_idx = OrderedDict(), OrderedDict()
    for r in cols:
        by_col.setdefault(r[0], []).append(r)
    for r in idx:
        by_idx.setdefault(r[0], []).append(r)

    # 按业务前缀分组：表名前缀就是域，这是本库既定的命名约定（db-design §1.4）
    DOMAIN = OrderedDict([
        ('iam_', '身份与权限'), ('sys_', '系统配置'), ('md_', '主数据'), ('dict_', '数据字典'),
        ('org_', '组织'), ('tnt_', '租户'), ('loc_', '场地与点位'), ('agt_', '代理商'),
        ('dev_', '设备'), ('gw_', '设备网关'), ('inv_', '库存'), ('wo_', '工单'),
        ('ord_', '订单'), ('pay_', '支付'), ('price_', '计价'), ('stl_', '结算'),
        ('acct_', '账务'), ('share_', '分润'), ('fin_', '财务'), ('usr_', '用户'),
        ('c_', 'C端用户'), ('mkt_', '营销'), ('cp_', '优惠券'), ('cs_', '客服'),
        ('ad_', '广告'), ('msg_', '消息通知'), ('notify_', '通知'), ('audit_', '审计'),
        ('rpt_', '报表'), ('flyway_', '迁移元数据'),
    ])

    def domain_of(t):
        for p, name in DOMAIN.items():
            if t.startswith(p):
                return '%s（`%s*`）' % (name, p)
        return '其他'

    grouped = OrderedDict()
    for t, tcomment, _rows in tables:
        grouped.setdefault(domain_of(t), []).append((t, tcomment))

    # 标准列（BaseEntity + 租户 + 审计）在 133 张表上重复出现 ≈ 1000 行，逐表列出来毫无信息量，
    # 还会把真正的业务列淹掉。改为：讲一次，逐表只标「齐备 / 缺哪些」。
    STD = OrderedDict([
        ('id', '主键，`BIGINT AUTO_INCREMENT`，全表统一'),
        ('tenant_id', '租户隔离键；全局表（字典/主数据）没有此列，实体用 `@TableName(excludeProperty="tenantId")` 排除'),
        ('created_at', '创建时间，MyBatis-Plus `INSERT` 自动填充'),
        ('created_by', '创建人；无登录主体时写 `SYSTEM`（与 `NULL`=漏填可区分）'),
        ('updated_at', '更新时间，`INSERT_UPDATE` 自动填充'),
        ('updated_by', '更新人，同上'),
        ('version', '乐观锁；追加表（append-only）没有此列'),
        ('deleted', '逻辑删除标记；追加表没有此列'),
    ])
    std_names = set(STD)

    n_tbl = len(tables)
    n_col = len(cols)
    n_biz = sum(1 for r in cols if r[1] not in std_names)
    biz_cols = [r for r in cols if r[1] not in std_names]
    filled = sum(1 for r in biz_cols if r[6])
    backfilled = sum(1 for r in biz_cols if not r[6] and (r[0], r[1]) in dc)
    still = len(biz_cols) - filled - backfilled
    no_entity = [t for t, _, _ in tables if t not in em and t != 'flyway_schema_history']

    L = []
    L.append('# 库表参考（`pb_core`）\n')
    L.append('> **本文件由脚本生成，不要手改** —— `python3 backend/scripts/gen-db-doc.py` 重生成。\n>')
    L.append('> 它只回答「现在到底是什么」。**为什么这么设计**看 [db-design.md](./db-design.md)，'
             '两份文档职责不重叠。\n')
    L.append('| | |\n|---|---|')
    L.append('| 表 | **%d** |' % n_tbl)
    L.append('| 列 | **%d**，其中业务列 **%d**、标准列 %d |' % (n_col, n_biz, n_col - n_biz))
    L.append('| 结构来源 | 实际库 `information_schema`（**不是 DDL 文件** —— '
             '`IF NOT EXISTS` 落到既有表上是空操作，文件写了不代表库里有） |')
    L.append('| 注释来源 | 业务列中库内 %d 列自带；另 %d 列库里为空、回落到 `docs/technical/ddl/` '
             '同名列（表格中标 `†`）；仍缺 %d 列 |' % (filled, backfilled, still))
    L.append('| 实体映射 | %d 张表有对应 Java 实体，%d 张没有 |' % (n_tbl - len(no_entity) - 1, len(no_entity)))
    L.append('')

    L.append('## 标准列（每张表都有，下文各表不再重复列出）\n')
    L.append('这 8 列由 `BaseEntity` + 租户 + 审计约定统一提供，在 %d 张表上共占 %d 列。'
             '逐表列出来只有噪音，会把真正的业务列淹掉，所以这里讲一次，'
             '各表只标注「标准列是否齐备」。\n' % (n_tbl, n_col - n_biz))
    L.append('| 列 | 说明 |\n|---|---|')
    for k, v in STD.items():
        L.append('| `%s` | %s |' % (k, v))
    L.append('')

    L.append('## 只有 `pb_core` 落地\n')
    L.append('设计里还有 `pb_pii`（个人信息隔离）与 `pb_auth`（凭证隔离）两个库，'
             '**开发库尚未创建**，相关字段目前仍在 `pb_core` 内。上线前必须拆出去，'
             '否则 PDPL 的「个人信息与业务数据物理隔离」这条对不上。\n')

    if no_entity:
        L.append('## 无对应实体的表（%d）\n' % len(no_entity))
        L.append('这些表没有 `@TableName` 指向，要么是纯关联表（由主实体的 mapper 直接操作），'
                 '要么是**建了但代码还没接**。后者在补业务逻辑时会被漏掉，逐张确认过再删本节。\n')
        L.append('`' + '` · `'.join(no_entity) + '`\n')

    # 目录
    L.append('## 目录\n')
    for dom, ts in grouped.items():
        anchor = re.sub(r'[^\w一-鿿-]', '', dom.replace(' ', '-')).lower()
        L.append('- **%s** — %d 张：%s' % (dom, len(ts), '、'.join('`%s`' % t for t, _ in ts)))
    L.append('')

    for dom, ts in grouped.items():
        L.append('\n---\n\n## %s\n' % dom)
        for t, tcomment in ts:
            ent = em.get(t)
            rows = by_col.get(t, [])
            present = {r[1] for r in rows}
            biz = [r for r in rows if r[1] not in std_names]

            L.append('### `%s`%s\n' % (t, ' — ' + tcomment if tcomment else ''))
            meta = ['实体 `%s`' % '`/`'.join(ent) if ent else '**无实体**',
                    '业务列 %d' % len(biz)]
            absent = [k for k in STD if k not in present]
            if not absent:
                meta.append('标准列齐备')
            else:
                # 缺 version/deleted 通常是刻意的（追加表不可改不可删），缺其他的是漏
                deliberate = {'version', 'deleted', 'tenant_id'}
                odd = [k for k in absent if k not in deliberate]
                meta.append('标准列缺 `%s`%s' % ('`/`'.join(absent),
                                                 ' ⚠️' if odd else '（追加表/全局表，符合预期）'))
            L.append('%s\n' % ' · '.join(meta))

            if not biz:
                L.append('*只有标准列。*\n')
            else:
                L.append('| 列 | 类型 | 空 | 默认 | 键 | 说明 |')
                L.append('|---|---|---|---|---|---|')
                for _, c, typ, nullable, default, key, comment, extra in biz:
                    mark = ''
                    if not comment and (t, c) in dc:
                        comment, mark = dc[(t, c)], ' †'
                    k = {'PRI': 'PK', 'UNI': 'UQ', 'MUL': 'IX'}.get(key, '')
                    if 'auto_increment' in extra:
                        k = (k + ' AI').strip()
                    L.append('| `%s` | `%s` | %s | %s | %s | %s%s |'
                             % (c, typ, '否' if nullable == 'NO' else '是',
                                ('`%s`' % default) if default not in ('—', 'NULL') else '—',
                                k, comment.replace('|', '\\|') or '—', mark))
            ix = [r for r in by_idx.get(t, []) if r[1] != 'PRIMARY']
            if ix:
                L.append('')
                L.append('索引：' + ' · '.join(
                    '`%s`(%s)%s' % (r[1], r[2], '' if r[3] == '1' else ' **UNIQUE**') for r in ix))
            L.append('')

    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('写出 %s' % os.path.relpath(OUT, ROOT))
    print('  表 %d · 列 %d' % (n_tbl, n_col))
    print('  业务列注释：库内 %d · DDL 回落 %d · 仍缺 %d' % (filled, backfilled, still))
    print('  无实体的表 %d 张' % len(no_entity))


if __name__ == '__main__':
    main()
