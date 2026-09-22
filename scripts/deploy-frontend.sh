#!/usr/bin/env bash
#
# powerbank 前端上线（ops-web / c-app）。规矩同 backend：锁、干净 HEAD 副本、
# 时间戳 SHA 版本标识、部署后校验、备份可回滚。详见 deploy/tencent/README.md §4。
#
# 用法：
#   scripts/deploy-frontend.sh ops-web
#   scripts/deploy-frontend.sh c-app
#   DRY=1 scripts/deploy-frontend.sh ops-web   # 只构建自检，不上传
set -euo pipefail

APP="${1:-}"
case "$APP" in
    ops-web)
        # ⚠️ NEXT_PUBLIC_USE_MOCK=0 必须给：默认是 mock，漏配就静默跑 mock，
        # ai-shop 2026-09-01 踩过 —— 「登录看似无权限」而请求根本没到后端。
        BUILD_CMD='NEXT_PUBLIC_USE_MOCK=0 NEXT_PUBLIC_API_BASE= npm run build'
        OUT='out'
        URL_PATH='/'
        ;;
    c-app)
        # ⚠️ H5_BASE 必须给：站在 /c/ 下，不给的话产物写成 `/assets/…` 而实际在
        # `/c/assets/…` —— **整站白屏而 index.html 200**。同 ai-shop 踩坑。
        BUILD_CMD='H5_BASE=/c/ VITE_API_BASE= npm run build:h5'
        OUT='dist/build/h5'
        URL_PATH='/c/'
        ;;
    *) echo "用法: $0 <ops-web|c-app>" >&2; exit 2 ;;
esac

HOST="${HOST:-soukmind-tx}"
WWW="${WWW:-/data/app/powerbank/web}"
DEST="$WWW/$APP"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say() { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die() { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }

HEAD_SHA="$(git rev-parse --short HEAD)"
HEAD_MSG="$(git log -1 --format=%s | cut -c1-46)"
say "$APP ← HEAD $HEAD_SHA  $HEAD_MSG"

# ── 锁（每前端一把，互不阻塞）
LOCKDIR="$WWW/.deploy-$APP.lock"
if ! ssh "$HOST" "mkdir '$LOCKDIR' 2>/dev/null"; then
    HOLDER="$(ssh "$HOST" "cat '$LOCKDIR/owner' 2>/dev/null" || true)"
    die "有人正在部署 $APP：${HOLDER:-（未知）}
    确认那个进程已死之后：ssh $HOST \"rm -rf '$LOCKDIR'\""
fi
ssh "$HOST" "echo '$(whoami)@$(hostname -s) $(date '+%F %T')' > '$LOCKDIR/owner'" || true

WT=""
cleanup() {
    [ -n "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1 || true
    ssh "$HOST" "rm -rf '$LOCKDIR'" >/dev/null 2>&1 || true
}
trap cleanup EXIT
ok "已拿到 $APP 部署锁"

# ── 从干净 HEAD 副本构建
WT="$(mktemp -d)/deploy-$APP"
git worktree add -q --detach "$WT" HEAD

say "构建（干净副本，$APP）"
(
    cd "$WT/$APP"
    npm install --no-audit --no-fund --loglevel=error 2>&1 | tail -5
    eval "$BUILD_CMD"
) || die "构建失败"

LOCAL_OUT="$WT/$APP/$OUT"
[ -d "$LOCAL_OUT" ] && [ -f "$LOCAL_OUT/index.html" ] || die "构建完了却没有 $LOCAL_OUT/index.html"

# 埋一个版本文件，方便线上核对与外部脚本探测
echo "$HEAD_SHA $(date '+%F %T') $HEAD_MSG" > "$LOCAL_OUT/VERSION"
ok "构建完成 $(du -sh "$LOCAL_OUT" | cut -f1)"

if [ "${DRY:-}" = "1" ]; then
    ok "DRY=1：到此为止，没有上传"
    exit 0
fi

# ── 上传（先落到临时目录，再原子替换）
TS="$(date +%Y%m%d-%H%M)"
STAGE="$WWW/.stage-$APP-$TS-$HEAD_SHA"
ssh "$HOST" "sudo mkdir -p '$WWW' && sudo chown -R deploy:deploy '$WWW'"
ssh "$HOST" "mkdir -p '$STAGE'"
say "上传到暂存目录 $STAGE"
rsync -az --delete "$LOCAL_OUT/" "$HOST:$STAGE/"

# ── 原子切换：备份现在的 → 移入新的
BACK="$WWW/$APP.bak-$TS"
ssh "$HOST" "test -d '$DEST' && mv '$DEST' '$BACK' || true; mv '$STAGE' '$DEST'"
ok "已切换 $DEST（备份在 $BACK）"

# ── 部署后校验
say "校验 $URL_PATH"
CODE="$(ssh "$HOST" "curl -sk -H 'Host: powerbank.ichain.top' -o /dev/null -w '%{http_code}' 'https://localhost$URL_PATH'")"
[ "$CODE" = "200" ] || die "校验失败：HTTP $CODE。可回滚：
    ssh $HOST \"rm -rf '$DEST' && mv '$BACK' '$DEST'\""
VER="$(ssh "$HOST" "curl -sk -H 'Host: powerbank.ichain.top' 'https://localhost$URL_PATH/VERSION' 2>/dev/null | head -1")"
[ -n "$VER" ] && ok "线上 VERSION：$VER" || ok "HTTP 200"

# ── 保留最近 3 份备份
ssh "$HOST" "ls -1dt '$WWW/$APP.bak-'* 2>/dev/null | tail -n +4 | xargs -r rm -rf" || true

printf '\n\033[32m前端上线完成\033[0m  %s  ←  %s %s\n' "$APP" "$HEAD_SHA" "$HEAD_MSG"
printf '回滚：ssh %s "rm -rf %s && mv %s %s"\n' "$HOST" "$DEST" "$BACK" "$DEST"
