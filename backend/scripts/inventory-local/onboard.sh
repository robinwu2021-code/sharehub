#!/bin/sh
# 本机联调（S5）：在独立进销存开 SHAREHUB 租户 → 业态档案 → 凭证。
# 业态档案 V210 起加：去向 SUPPLIER；入库 OPENING / TRANSFER_IN；出库 REQUISITION / RETURN_SUPPLIER / TRANSFER_OUT（物料单据与处置出库）。
# 已开过租户的只更新档案：profile.sh
# 用法：onboard.sh [凭证输出文件] [业主外部编号]（默认 ~/.local/etc/inventory-sharehub-cred.json、powerbank-local；凭证权限 600，不进仓库）
# 一个 powerbank 库对应一个业主：test_sharehub 用 powerbank-local，本地演示库 pb_local 用 powerbank-pb-local，账不混。
# 前提：进销存已按 .claude/launch.json 的 inventory-svc-local 启动（管理令牌 local-admin-token）
set -e
OUT=${1:-$HOME/.local/etc/inventory-sharehub-cred.json}
EXT=${2:-powerbank-local}
B=http://127.0.0.1:8086/api/inventory/admin/v1/owners
H1="X-Inventory-Admin-Token: local-admin-token"; H2="Content-Type: application/json"; H3="X-Inventory-Actor: STAFF:setup"
OID=$(curl -s -m 10 -H "$H1" -H "$H2" -H "$H3" -X POST "$B" -d "{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"$EXT\",\"name\":\"ShareHub $EXT\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["ownerId"])')
echo "ownerId=$OID"
PROFILE='{"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],"targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},{"code":"SITE","nameKey":"s","parentType":"MERCHANT"},{"code":"PERSON","nameKey":"p","parentType":null},{"code":"SUPPLIER","nameKey":"sp","parentType":null}],"inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false},{"code":"RETURN","basisRequired":false,"attachmentRequired":false},{"code":"CORRECTION","basisRequired":false,"attachmentRequired":false},{"code":"OPENING","basisRequired":false,"attachmentRequired":false},{"code":"TRANSFER_IN","basisRequired":false,"attachmentRequired":false}],"outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],"optionalTargets":["SITE"],"handover":"POOL"},{"code":"SCRAP","requiredTargets":[],"optionalTargets":[],"handover":"NONE"},{"code":"REQUISITION","requiredTargets":[],"optionalTargets":[],"handover":"NONE"},{"code":"RETURN_SUPPLIER","requiredTargets":["SUPPLIER"],"optionalTargets":[],"handover":"NONE"},{"code":"TRANSFER_OUT","requiredTargets":[],"optionalTargets":[],"handover":"NONE"}],"gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}'
curl -s -m 10 -H "$H1" -H "$H2" -H "$H3" -X PUT "$B/$OID/profile" -d "{\"profile\":$PROFILE,\"reason\":\"local integration\"}" -w " profile:%{http_code}\n"
curl -s -m 10 -H "$H1" -H "$H2" -H "$H3" -X POST "$B/$OID/credentials" -d '{"name":"sharehub-local"}' > "$OUT"
chmod 600 "$OUT"
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print("credential written:", bool(d.get("appKey")) and bool(d.get("appSecret")), "->", sys.argv[1])' "$OUT"
