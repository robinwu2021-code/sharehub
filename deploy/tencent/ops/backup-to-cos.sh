#!/usr/bin/env bash
# 每日库备份（README §8；此前只在 README 里描述、脚本不在仓库 —— 运营方原始需求 SEC-07 / DLV-12，2026-10-29 补）
#
#   mysqldump（一致性快照，不锁表）→ gzip → 上传 COS → 本机只留最近 KEEP_DAYS 天
#
# 由 /etc/cron.d/powerbank-backup 每天 03:30 调用（避开 ai-shop 03:20 的备份）。
# 配置全走环境变量（同 sharehub-app.env，600 权限），本脚本不含任何口令：
#   PB_DB_HOST / PB_DB_PORT / PB_DB_NAME / PB_DB_USER / PB_DB_PASS
#   PB_BACKUP_DIR        本机目录，默认 /data/backup/powerbank/db
#   PB_BACKUP_KEEP_DAYS  本机保留天数，默认 3
#   PB_BACKUP_UPLOAD     上传命令模板，{file} 会替换成备份文件路径；空 = 只留本机（演练 / 无 COS 时）
#                        生产示例：coscli cp {file} cos://hxmall-backup-1301656997/db/powerbank/
# 退出码非 0 = 备份失败，cron 的 MAILTO / 监控据此告警。
set -euo pipefail

: "${PB_DB_HOST:=127.0.0.1}" "${PB_DB_PORT:=3306}" "${PB_DB_NAME:=pb_core}" "${PB_DB_USER:?PB_DB_USER 未设}"
: "${PB_DB_PASS?PB_DB_PASS 未设（无口令的本地账号请设为空串）}" "${PB_BACKUP_DIR:=/data/backup/powerbank/db}" "${PB_BACKUP_KEEP_DAYS:=3}"
: "${PB_BACKUP_UPLOAD:=}" "${MYSQLDUMP:=mysqldump}"

mkdir -p "$PB_BACKUP_DIR"
stamp=$(date +%Y%m%d-%H%M%S)
file="$PB_BACKUP_DIR/${PB_DB_NAME}-${stamp}.sql.gz"
tmp="$file.part"

# 口令经环境变量传给客户端，不出现在进程列表里
export MYSQL_PWD="$PB_DB_PASS"
# MySQL 8+ 的 mysqldump 默认写 GTID 信息，会为此要 RELOAD / FLUSH_TABLES 权限；备份账号不该有这些权限，
# 单库逻辑备份也用不着 GTID —— 客户端支持时关掉。MariaDB 的 mysqldump 没有这个选项，不加。
extra=()
# 先取全文再匹配：pipefail 下 `--help | grep -q` 会因 grep 提前退出让上游 SIGPIPE，整条管道判失败、选项加不上
help_text=$("$MYSQLDUMP" --help 2>/dev/null || true)
case "$help_text" in *--set-gtid-purged*) extra+=(--set-gtid-purged=OFF) ;; esac
# --single-transaction：InnoDB 一致性快照，备份期间业务照常写
# --routines --triggers：存储过程与触发器一并带走；--hex-blob：二进制列不被字符集破坏
"$MYSQLDUMP" -h "$PB_DB_HOST" -P "$PB_DB_PORT" -u "$PB_DB_USER" \
  --single-transaction --routines --triggers --hex-blob --default-character-set=utf8mb4 ${extra[@]+"${extra[@]}"} \
  "$PB_DB_NAME" | gzip -c > "$tmp"
unset MYSQL_PWD

# 空文件或截断的 gzip 不算备份成功（磁盘满时 mysqldump 可能写出半截）
gzip -t "$tmp"
[ "$(gzip -dc "$tmp" | tail -c 200 | grep -c 'Dump completed')" -ge 1 ] || { echo "备份不完整：缺 Dump completed 尾标 $tmp" >&2; exit 2; }
mv "$tmp" "$file"
echo "备份完成 $file ($(du -h "$file" | cut -f1))"

if [ -n "$PB_BACKUP_UPLOAD" ]; then
  cmd=${PB_BACKUP_UPLOAD//\{file\}/$file}
  eval "$cmd"
  echo "已上传：$cmd"
fi

find "$PB_BACKUP_DIR" -name "${PB_DB_NAME}-*.sql.gz" -mtime +"$PB_BACKUP_KEEP_DAYS" -print -delete
