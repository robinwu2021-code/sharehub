#!/usr/bin/env bash
# 本地联调的令牌与种子（服务须以 local 配置档运行：scripts/dev-run.sh）。
#   dev-token.sh alice                 # 按人设签成员令牌（alice / bob / dave / erin / frank / viewer / auditor / admin）
#   dev-token.sh --sub u_x --perms a,b # 不用人设：任意宿主编号（不存在即开通）与宿主权限
#   dev-token.sh seed                  # 种子清单（账户 · 空间 · 人设 · 单号）
#   dev-token.sh fixtures              # 生成前端 e2e 夹具（fixtures.opskit.json 的内容，令牌为 alice）
#   服务刚起来时种子可能还没写完：/dev/seed 返回 503 表示「稍候」，脚本会等它就绪
# 调用示例：
#   curl -s -X POST localhost:8090/api/opskit/v1/workspaces/ext:T001/todos/count -H "Authorization: Bearer $(dev-token.sh alice)"
set -euo pipefail
BASE="${OPSKIT:-http://localhost:8090}"
case "${1:-}" in
  ""|-h|--help) sed -n '2,9p' "$0"; exit 0 ;;
  seed) for i in $(seq 1 60); do out="$(curl -sf "$BASE/dev/seed" 2>/dev/null)" && { echo "$out"; exit 0; }; sleep 1; done; echo "种子 60 秒内未就绪" >&2; exit 1 ;;
  fixtures)
    seed="$("$0" seed)"
    token="$(curl -sf -X POST "$BASE/dev/token" -H 'Content-Type: application/json' -d '{"as":"alice"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
    python3 - "$seed" "$token" <<'PY'
import json, sys
s, token = json.loads(sys.argv[1]), sys.argv[2]
f = s["fixtures"]
print(json.dumps({
  "_note": "由 opskit/scripts/dev-token.sh fixtures 生成；服务重启后单号会变，需重新生成。勿提交令牌",
  "account": s["accountId"], "workspace": s["workspacePath"],
  "approvalTwoStep": f["approvalTwoStep"]["approvalNo"], "approvalMine": f["approvalMine"]["approvalNo"],
  "approvalHidden": f["approvalHidden"]["approvalNo"], "queueGroup": f["queueGroup"], "queueTasks": f["queueTasks"],
  "claimedByOther": f["claimedByFrank"], "claimTimeoutSec": f["claimTimeoutSec"],
  "ticketAssigned": f.get("ticketAssigned", ""), "ticketQueued": f.get("ticketQueued", ""), "ticketUrgent": f.get("ticketUrgent", ""),
  "ticketQueue": f.get("ticketQueue", ""), "token": token}, ensure_ascii=False, indent=2))
PY
    exit 0 ;;
  --sub)
    sub="$2"; perms="${4:-}"
    body=$(python3 -c 'import json,sys;print(json.dumps({"sub":sys.argv[1],"perms":[p for p in sys.argv[2].split(",") if p]}))' "$sub" "$perms") ;;
  *) body="{\"as\":\"$1\"}" ;;
esac
curl -sf -X POST "$BASE/dev/token" -H 'Content-Type: application/json' -d "$body" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])'
