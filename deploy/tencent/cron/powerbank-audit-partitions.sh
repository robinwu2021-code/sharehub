#!/usr/bin/env bash
# powerbank · 审计表分区维护（配合 V61__audit_monthly_partitions.sql）
#
# ## 它只做一件事：把未来几个月的分区提前备好
#
# **从不删任何分区。** 审计是争议时的证据，删它必须是有人明确决定的动作，
# 不该由一个 cron 在半夜替人做。归档的命令写在本文件末尾，手动执行。
#
# ## 为什么"不加也不会立刻出事"，但仍然必须加
#
# V61 留了 pmax 兜底分区，所以忘了加分区插入也不会失败 —— 这是有意的：
# RANGE 分区最常见的事故是某天零点起所有插入报错，而对审计表来说，
# 写失败是降级不是 500（见 AuditTrailInterceptor），于是
# **那个事故不会有任何人察觉**，直到需要查审计时发现断了几个月。
#
# 但 pmax 是兜底不是归宿：数据全堆在里面，就失去了按月归档的能力
# （不能 DROP pmax）。本脚本把它们及时拆分出去。
#
# ## REORGANIZE 会重建 pmax 里的数据
#
# 正常情况 pmax 是空的，操作是秒级。若脚本几个月没跑，pmax 里积了数据，
# REORGANIZE 会真的搬它们 —— 结果仍然正确，只是慢，且期间持锁。
# 所以：宁可每天跑一次空转，不要攒着。
#
# ## 凭据
#
# **不接受命令行密码**（会进 ps 与 shell history）。用 MySQL 选项文件：
#   [client]
#   user=powerbank
#   password=...
# 放 /etc/powerbank/db.cnf，权限 600，属主 root。
#
# 安装：
#   sudo install -m 755 powerbank-audit-partitions.sh /usr/local/bin/
#   sudo tee /etc/cron.d/powerbank-audit-partitions <<'EOF'
#   30 4 * * * root /usr/local/bin/powerbank-audit-partitions.sh >/dev/null 2>&1
#   EOF
# 先手动跑一次并看输出，确认它真的加了分区再装 cron
# （改完这类脚本必须确认落点 —— 空跑和"本来就不用加"长得一模一样）。

set -euo pipefail

CNF="${POWERBANK_DB_CNF:-/etc/powerbank/db.cnf}"
DB="${POWERBANK_DB:-pb_core}"
TABLE="iam_audit_log"
# 提前备几个月。3 个月意味着脚本连续坏掉三个月才会开始往 pmax 堆 ——
# 留足发现问题的时间，又不至于建一堆空分区。
AHEAD="${POWERBANK_PARTITION_AHEAD:-3}"

if [[ ! -r "$CNF" ]]; then
    echo "读不到 MySQL 选项文件 $CNF —— 不接受命令行密码，见本文件顶部说明" >&2
    exit 1
fi

mysql_do() { mysql --defaults-extra-file="$CNF" -N -B "$DB" -e "$1"; }

existing="$(mysql_do "
  SELECT partition_name FROM information_schema.partitions
   WHERE table_schema='$DB' AND table_name='$TABLE' AND partition_name IS NOT NULL")"

if [[ -z "$existing" ]]; then
    echo "$TABLE 没有分区 —— V61 迁移没跑？先确认再说，本脚本不替它建" >&2
    exit 1
fi
if ! grep -qx "pmax" <<<"$existing"; then
    echo "$TABLE 没有 pmax 兜底分区 —— 有人手工改过分区。" >&2
    echo "先把它加回来（见 V61 的说明），否则某天插入会直接失败而没人发现。" >&2
    exit 1
fi

added=0
# 基准日**必须是当月 1 号**，不能用今天。GNU date 的 "+1 month" 在
# 1 月 31 日会算出 3 月 3 日（2 月没有 31 号，它溢出到下个月）——
# 于是某些月份会被整个跳过，而跳过的那个月的数据默默落进 pmax。
# 从 1 号起算就不存在这个问题：每个月都有 1 号。
for ((i = 1; i <= AHEAD; i++)); do
    month="$(date -u -d "$(date -u +%Y-%m-01) +${i} month" +%Y_%m)"
    bound="$(date -u -d "$(date -u +%Y-%m-01) +$((i + 1)) month" +%Y-%m-01)"
    name="p${month}"
    grep -qx "$name" <<<"$existing" && continue

    # REORGANIZE 是把 pmax 拆成「新分区 + 新的 pmax」；顺序不能反，
    # MAXVALUE 必须始终是最后一个。
    mysql_do "ALTER TABLE $TABLE REORGANIZE PARTITION pmax INTO (
                PARTITION $name VALUES LESS THAN ('$bound'),
                PARTITION pmax VALUES LESS THAN (MAXVALUE))"
    echo "已加分区 $name（< $bound）"
    existing="${existing}"$'\n'"${name}"
    added=$((added + 1))
done

echo "分区维护完成：新增 ${added} 个"

# ——————————————————————————————————————————————————————————
# 归档（手动执行，不在本脚本里自动做）
#
# 1. 先导出要归档的那个月，**确认文件可读**再往下走：
#      mysqldump --defaults-extra-file=/etc/powerbank/db.cnf \
#        pb_core iam_audit_log --where="created_at >= '2026-09-01' \
#        AND created_at < '2026-10-01'" | gzip -9 > audit-2026-09.sql.gz
#      gzip -t audit-2026-09.sql.gz && zcat audit-2026-09.sql.gz | head
#
# 2. 确认无误后再删那个分区（**这一步不可逆**）：
#      ALTER TABLE iam_audit_log DROP PARTITION p2026_09;
#
# 顺序反过来做过一次就明白了：先删再发现导出是空的，那段历史就没了。
# ——————————————————————————————————————————————————————————
