# -*- coding: utf-8 -*-
"""实体 ⊖ 库表列 差异扫描。

为什么重写：上一版按**文件**取首个 @TableName，而 `IamEntities.java` 一个文件里写了
role/menu/permission/data_scope 四个实体 —— 结果四个实体的字段全被算到 iam_role 头上，
误报"缺 15 列"。本版按 **@TableName 逐个类**切分（大括号配对），并从 **Java 字段类型**
推导 SQL 类型（比从列名猜可靠）。

用法：python3 backend/scripts/entity-column-diff.py
输出：/tmp/entity_diff.json  +  控制台摘要
"""
import io, json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _modules import source_roots  # noqa: E402
SRC_ROOTS = source_roots()
DB = ['mysql', '-upowerbank', '-ppowerbank', '-h127.0.0.1', 'pb_core', '-N', '-B', '-e']

# Java 类型 → SQL 类型。长度/精度不确定的标 ?，交给人/agent 对照 db-design 定。
JAVA2SQL = {
    'Long': 'BIGINT', 'Integer': 'INT', 'Boolean': 'TINYINT(1)',
    'BigDecimal': 'DECIMAL(18,2)?',      # 金额默认；比率应为 DECIMAL(5,4)
    'LocalDateTime': 'DATETIME(3)', 'LocalDate': 'DATE',
    'String': 'VARCHAR(?)',
}


def camel(col):
    """下划线列名 → 驼峰（与比对侧的 camel→snake 还原互逆）。"""
    head, *rest = col.split('_')
    return head + ''.join(x[:1].upper() + x[1:] for x in rest)


def base_class_fields():
    """扫 BaseEntity 等父类的字段。

    **不做这一步，整个卡口对继承体系是失效的**：实体自有字段与表对得上就报 0 缺口，
    而父类带来的 version/deleted/tenant_id/审计四列缺不缺根本没检查。
    实测代价：`price_rule` 缺 version/deleted 一直没被发现，
    直到 M2 的取价链第一次 SELECT 它才炸成 Unknown column 'version'
    —— 卡口报绿了几十次，但那是假绿。
    """
    bases = {}
    for _root in SRC_ROOTS:
     for dirpath, _, files in os.walk(_root):
        for fn in files:
            if fn not in ('BaseEntity.java',):
                continue
            s2 = io.open(os.path.join(dirpath, fn), encoding='utf-8').read()
            cls = os.path.splitext(fn)[0]
            fields = {}
            for fm in re.finditer(
                    r'private\s+(?:static\s+|final\s+)*([A-Za-z_][\w.<>]*)\s+(\w+)\s*[;=]', s2):
                fields[fm.group(2)] = fm.group(1).split('.')[-1].split('<')[0]
            bases[cls] = fields
    return bases


def entities():
    """按 @TableName 逐类切分，返回 [(table, className, file, {field: javaType})]"""
    bases = base_class_fields()
    out = []
    for _root in SRC_ROOTS:
     for dirpath, _, files in os.walk(_root):
        for fn in files:
            if not fn.endswith('.java'):
                continue
            p = os.path.join(dirpath, fn)
            s = io.open(p, encoding='utf-8').read()
            for m in re.finditer(r'@TableName\(\s*(?:value\s*=\s*)?"(\w+)"', s):
                table = m.group(1)
                # 从注解往后找 class/record 声明，再做大括号配对取类体
                cm = re.search(r'\b(?:class|record)\s+(\w+)', s[m.end():])
                if not cm:
                    continue
                cls = cm.group(1)
                start = s.find('{', m.end() + cm.end())
                if start < 0:
                    continue
                depth, i = 0, start
                while i < len(s):
                    if s[i] == '{': depth += 1
                    elif s[i] == '}':
                        depth -= 1
                        if depth == 0: break
                    i += 1
                body = s[start:i]
                # 只取本类直属字段（嵌套类的字段在更深的大括号里，这里简单地按声明前缀过滤）
                fields = {}
                for fm in re.finditer(r'private\s+(?:static\s+|final\s+)*([A-Za-z_][\w.<>]*)\s+(\w+)\s*[;=]', body):
                    jt = fm.group(1).split('.')[-1].split('<')[0]
                    name = fm.group(2)
                    # @TableField("real_column") 显式改名的字段**按注解名算**，不按字段名推导。
                    # 不读它的代价是实打实的：V13 因此把 WoHandle.assigneeNo（注解指向已存在的
                    # assignee_id）误判成「缺 assignee_no 列」，给 6 张表加了一批**永远为 NULL
                    # 的影子列**（且被标成 JSON）。脚本读不到的映射，迁移就会照着幻觉建表。
                    ann = re.search(r'@TableField\(\s*(?:value\s*=\s*)?"(\w+)"[^)]*\)\s*(?:@\w+[^\n]*\s*)*'
                                    r'private[^;]*\b' + re.escape(name) + r'\s*[;=]', body)
                    fields[camel(ann.group(1)) if ann else name] = jt
                # 合并父类字段。`@TableName(excludeProperty=...)` 排除的列不算
                # （全局表用它排掉 tenantId —— 那些表确实没有该列，是有意的）。
                ext = re.search(r'\bclass\s+' + re.escape(cls) + r'\s+extends\s+(\w+)', s)
                if ext and ext.group(1) in bases:
                    excluded = set()
                    ann = s[m.start():m.end() + 200]
                    ex = re.search(r'excludeProperty\s*=\s*\{?([^})]*)', ann)
                    if ex:
                        excluded = {x.strip().strip('"') for x in ex.group(1).split(',')}
                    for k, v in bases[ext.group(1)].items():
                        if k not in fields and k not in excluded:
                            fields[k] = v
                out.append((table, cls, os.path.relpath(p, ROOT), fields))
    return out


def snake(n):
    return re.sub(r'([A-Z])', lambda m: '_' + m.group(1).lower(), n)


def db_columns(table):
    """查表的列。**查库失败必须炸，不能返回空集**。

    原实现忽略 returncode 直接吃 stdout：连库失败（此前少了 -p 密码）时返回空集，
    主流程把空集当成「表不存在」静默 continue —— 于是 **135 张表全部被跳过、
    脚本永远报「缺列 0 张」**。一个永远绿的卡口比没有卡口更危险：
    它让人以为检查过了。
    """
    r = subprocess.run(DB + [
        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA='pb_core' AND TABLE_NAME='%s'" % table],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit('查库失败（%s）：%s' % (table, r.stderr.strip()))
    return set(x for x in r.stdout.split() if x)


BASE_ENTITY_FIELDS = {'id', 'tenantId', 'createdAt', 'createdBy', 'updatedAt', 'updatedBy', 'version', 'deleted'}


def main():
    ents = entities()
    print("扫到 %d 个实体（按 @TableName 逐类切分）" % len(ents))
    multi = {}
    for t, c, f, _ in ents:
        multi.setdefault(f, []).append(c)
    shared = {f: cs for f, cs in multi.items() if len(cs) > 1}
    if shared:
        print("其中「一文件多实体」%d 个文件（旧脚本正是在这里出错）：" % len(shared))
        for f, cs in sorted(shared.items()):
            print("   %-58s %s" % (os.path.basename(f), ", ".join(cs)))

    result, total_missing = {}, 0
    for table, cls, path, fields in sorted(ents):
        have = db_columns(table)
        if not have:
            result[table] = {'_status': 'TABLE_MISSING', '_class': cls}
            continue
        miss = {}
        for fld, jt in fields.items():
            col = snake(fld)
            if col in have or col == 'id':
                continue
            miss[col] = {'field': fld, 'java': jt, 'sqlGuess': JAVA2SQL.get(jt, 'VARCHAR(?)')}
        if miss:
            result[table] = {'_class': cls, '_file': path, 'missing': miss}
            total_missing += len(miss)

    io.open('/tmp/entity_diff.json', 'w', encoding='utf-8').write(
        json.dumps(result, ensure_ascii=False, indent=2))

    real = {k: v for k, v in result.items() if 'missing' in v}
    print("\n缺列的表 %d 张，共 %d 列：" % (len(real), total_missing))
    for t in sorted(real):
        cols = real[t]['missing']
        need_check = [c for c, d in cols.items() if '?' in d['sqlGuess']]
        print("  %-24s %-22s 缺 %2d 列%s" % (t, real[t]['_class'], len(cols),
              ("  需定长度/精度: " + ",".join(sorted(need_check))[:60]) if need_check else ""))
    print("\n明细 → /tmp/entity_diff.json")


if __name__ == '__main__':
    main()
