#!/bin/sh
# 本机联调：已开过 SHAREHUB 租户时只更新业态档案（档案定义唯一在 onboard.sh 的 PROFILE）。
# 用法：profile.sh <ownerId>   （ownerId 查法：select owner_id from inventory_test.inv_owner where system_code='SHAREHUB'）
set -e
OID=${1:?ownerId required}
B=http://127.0.0.1:8086/api/inventory/admin/v1/owners
H1="X-Inventory-Admin-Token: local-admin-token"; H2="Content-Type: application/json"; H3="X-Inventory-Actor: STAFF:setup"
PROFILE=$(sed -n "s/^PROFILE='\(.*\)'$/\1/p" "$(dirname "$0")/onboard.sh")
curl -s -m 10 -H "$H1" -H "$H2" -H "$H3" -X PUT "$B/$OID/profile" -d "{\"profile\":$PROFILE,\"reason\":\"materials and disposal purposes (V210)\"}" -w " profile:%{http_code}\n"
