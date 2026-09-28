#!/usr/bin/env bash
# 流程与工单端到端场景：建隔离副本 → 静态构建 → 无头 Chrome 跑场景（scripts/flow-e2e/）。
#
#   ops-web/scripts/flow-e2e.sh                       # 对 mock 跑全部场景
#   ops-web/scripts/flow-e2e.sh --only S2,I-ar        # 只跑某些（按 id 前缀）
#   OPSKIT=http://localhost:8090 OPSKIT_WS=ext:T001 OPSKIT_ACCOUNT=acc_1 ops-web/scripts/flow-e2e.sh
#                                                     # 对真实 OpsKit 跑（夹具读 fixtures.opskit.json）
#
# 为什么要副本：dev server 与 build 共用 .next，同时跑会互相污染（CLAUDE.md 坑 3）；
# 副本在 .e2e/flow-e2e-<模式>/，软链 node_modules，turbopack.root 指仓库根（否则不认软链）。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
MODE=$([[ -n "${OPSKIT:-}" ]] && echo opskit || echo mock)
DIR=".e2e/flow-e2e-$MODE"

mkdir -p "$DIR"
rsync -a --delete --exclude node_modules --exclude .next --exclude out --exclude tsconfig.tsbuildinfo ops-web/ "$DIR/"
[[ -e "$DIR/node_modules" ]] || ln -s "$ROOT/ops-web/node_modules" "$DIR/node_modules"
python3 - "$DIR/next.config.ts" "$ROOT" <<'PY'
import sys
p, root = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
if "turbopack:" not in s:
    s = s.replace("  experimental: {", f'  turbopack: {{ root: "{root}" }}, // 仅验证副本\n  experimental: {{', 1)
    open(p, "w", encoding="utf-8").write(s)
PY

if [[ "$MODE" == opskit ]]; then
  export NEXT_PUBLIC_USE_MOCK=0 NEXT_PUBLIC_FLOW_API=http NEXT_PUBLIC_OPSKIT_WS="${OPSKIT_WS:?要设 OPSKIT_WS}" NEXT_PUBLIC_OPSKIT_ACCOUNT="${OPSKIT_ACCOUNT:-}"
fi
echo "▶ 构建（$MODE）…"
(cd "$DIR" && npx next build > "$ROOT/.e2e/build-$MODE.log" 2>&1) || { echo "构建失败，见 .e2e/build-$MODE.log"; exit 1; }

echo "▶ 跑场景…"
node ops-web/scripts/flow-e2e/run.mjs --out "$DIR/out" ${OPSKIT:+--opskit "$OPSKIT"} "$@"
