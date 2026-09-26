#!/usr/bin/env bash
# ============================================================
# powerbank · 演示环境心跳模拟器
#
# ⚠️ **仅供演示环境。接入真实设备前必须删掉它和 /etc/cron.d/powerbank-demo-heartbeat。**
#
# 【为什么需要它】
# 在线判定的口径是「最近心跳在 ONLINE_WINDOW_MIN（3 分钟）内」。演示环境的种子机柜
# 没有真设备在上报，于是全部被判离线 —— 告警判定随即为它们挂上整柜 STOP_RENT，
# C 端一颗宝也借不到。发一次心跳没用：3 分钟后又掉回去。所以要每分钟发一次。
#
# 【为什么不是把那个 3 分钟窗口调大】
# 那个窗口是真实业务语义：设备掉线 3 分钟就该停借。为了演示把它调成 30 天，
# 等于把这条闸关掉，而将来没有人会记得调回来。
# **宁可让演示环境多一个一眼看得出是假的模拟器，也不要改业务口径。**
#
# 【为什么只发 CAB1 号段】
# 真设备接入后它们有自己的心跳。模拟器如果连真设备一起刷，
# 真设备掉线了你也看不出来 —— 那比没有模拟器危险得多。
# ⚠️ 因此**真设备的柜号必须避开 CAB1 前缀**，这一条要写进设备接入准备清单。
# ============================================================
set -uo pipefail

ENV_FILE=/data/app/powerbank/sharehub-app/sharehub-app.env
BASE=http://127.0.0.1:8082
PREFIX='CAB1'          # 只刷这个号段
BATCH=50               # 单次请求携带的事件数

TOKEN=$(grep -oP '(?<=^SHAREHUB_INTERNAL_TOKEN=).*' "$ENV_FILE" 2>/dev/null)
if [ -z "${TOKEN:-}" ]; then
  echo "SHAREHUB_INTERNAL_TOKEN 未配置，/internal/events/device 会 401 —— 模拟器退出" >&2
  exit 1
fi

# 只取「已布放」的：故障柜与在库柜本来就不该有心跳，
# 给它们发等于抹掉「这台柜子坏了 / 还没装」这个事实。
NOS=$(mariadb -N -e "SELECT cabinet_no FROM pb_core.dev_cabinet
                     WHERE cabinet_no LIKE '${PREFIX}%' AND status='DEPLOYED' AND deleted=0" 2>/dev/null)
[ -z "$NOS" ] && exit 0

# occurredAt 用**服务器本地时间**：后端字段是 LocalDateTime（无时区），
# 发 UTC 会被当成 8 小时前的心跳，判定照样离线。
NOW=$(date +%Y-%m-%dT%H:%M:%S)
STAMP=$(date +%s)

printf '%s\n' $NOS | xargs -n "$BATCH" | while read -r line; do
  events=$(for no in $line; do
    printf '{"eventId":"demo-hb-%s-%s","type":"HEARTBEAT","deviceNo":"%s","occurredAt":"%s"},' \
      "$no" "$STAMP" "$no" "$NOW"
  done)
  curl -s -o /dev/null -m 20 -X POST "$BASE/internal/events/device" \
    -H 'Content-Type: application/json' -H "X-Internal-Token: $TOKEN" \
    -d "{\"events\":[${events%,}]}"
done
