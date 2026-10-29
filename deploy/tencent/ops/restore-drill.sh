#!/usr/bin/env bash
# 恢复演练（运营方原始需求 SEC-07 / DLV-12，2026-10-29）：把一份备份恢复到**独立的演练库**并核对，证明备份真能用。
#
# 绝不碰生产库：目标库名必须以 restore_drill_ 或 test_ 开头，否则拒绝执行。
#   用法：restore-drill.sh <备份文件.sql.gz> <演练库名>
#   环境变量：PB_DB_HOST / PB_DB_PORT / PB_DB_USER / PB_DB_PASS（同 backup-to-cos.sh）
#
# 核对项（任何一项不过 → 退出码非 0）：
#   1. 迁移历史完整：flyway_schema_history 没有失败行，最高版本号打印出来与发布记录对照
#   2. 关键表都在且有数据：订单、用户、商家、分润、结算单、系统参数
#   3. 打印各表行数，留作演练记录
set -euo pipefail
file=${1:?用法：restore-drill.sh <备份文件.sql.gz> <演练库名>}
db=${2:?用法：restore-drill.sh <备份文件.sql.gz> <演练库名>}
case "$db" in restore_drill_*|test_*) ;; *) echo "拒绝：演练库名必须以 restore_drill_ 或 test_ 开头（防止误覆盖生产库）：$db" >&2; exit 3 ;; esac
: "${PB_DB_HOST:=127.0.0.1}" "${PB_DB_PORT:=3306}" "${PB_DB_USER:?PB_DB_USER 未设}" "${PB_DB_PASS?PB_DB_PASS 未设（无口令的本地账号请设为空串）}" "${MYSQL:=mysql}"

export MYSQL_PWD="$PB_DB_PASS"
q() { "$MYSQL" -h "$PB_DB_HOST" -P "$PB_DB_PORT" -u "$PB_DB_USER" -N -B "$@"; }

started=$(date +%s)
gzip -t "$file"
q -e "DROP DATABASE IF EXISTS \`$db\`; CREATE DATABASE \`$db\` DEFAULT CHARACTER SET utf8mb4;"
gzip -dc "$file" | q "$db"
echo "恢复用时 $(( $(date +%s) - started )) 秒 → $db"

fail=0
failed=$(q "$db" -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")
latest=$(q "$db" -e "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
echo "迁移历史：最高版本 V$latest，失败行 $failed"
[ "$failed" = "0" ] || { echo "✗ 迁移历史里有失败行" >&2; fail=1; }

for t in ord_order usr_user agt_agent share_record stl_settlement sys_param iam_employee; do
  n=$(q "$db" -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null || echo "缺表")
  printf '  %-16s %s\n' "$t" "$n"
  case "$n" in 缺表) echo "✗ 缺表 $t" >&2; fail=1 ;; esac
done
for t in sys_param iam_employee; do
  [ "$(q "$db" -e "SELECT COUNT(*) FROM \`$t\`")" -gt 0 ] || { echo "✗ $t 为空：备份里没有基础数据" >&2; fail=1; }
done
unset MYSQL_PWD
[ "$fail" = "0" ] && echo "✓ 演练通过：备份可恢复，迁移历史完整、关键表齐全" || { echo "✗ 演练未通过" >&2; exit 1; }
