#!/usr/bin/env bash
#
# powerbank 后端上线。规矩逐条搬自 ai-shop/scripts/deploy-backend.sh —— 详见
# deploy/tencent/README.md §4。这里只是本项目的落地版本。
#
# 用法：
#   scripts/deploy-backend.sh               # 从当前 HEAD 打包并上线 sharehub-app
#   HOST=soukmind-tx scripts/deploy-backend.sh
#   DRY=1 scripts/deploy-backend.sh         # 只打包与传，不切软链、不重启
#   scripts/deploy-backend.sh --rollback    # 切回上一版并守 health=200
set -euo pipefail

HOST="${HOST:-soukmind-tx}"

APP="sharehub-app"
MVN_MODULE="sharehub-app"
JAR_IN_REPO="sharehub-app/target/sharehub-app-0.1.0-SNAPSHOT.jar"
REMOTE_DIR="/data/app/powerbank/sharehub-app"
LINK_NAME="sharehub-app.jar"
LINK="$REMOTE_DIR/$LINK_NAME"
SERVICE="powerbank"
HEALTH="http://localhost:8082/actuator/health"
HEALTH_OK="200"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say()  { printf '\033[36m›\033[0m %s\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; exit 1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1" >&2; }

# ── health 守候：60×3s 不到就 die（ai-shop 2026-08-28 事故的产物）
wait_healthy() {
    ssh "$HOST" "for i in \$(seq 1 60); do
            c=\$(curl -s -o /dev/null -w '%{http_code}' '$HEALTH');
            [ \"\$c\" = '$HEALTH_OK' ] && { echo \"health=$HEALTH_OK（约 \$((i*3)) 秒）\"; exit 0; };
            sleep 3;
        done; echo \"health=\$c\"; exit 1"
}

# ── JDK 21 强检查（父 POM enforcer 卡死）
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    for c in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
             /usr/lib/jvm/java-21-openjdk-amd64; do
        [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
    done
fi
[ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21' \
    || die "找不到 JDK 21（父 POM 的 enforcer 要求它）"

HEAD_SHA="$(git rev-parse --short HEAD)"
HEAD_MSG="$(git log -1 --format=%s | cut -c1-60)"
say "本次要上线的 HEAD = $HEAD_SHA  $HEAD_MSG"

# ── 出发时线上是什么（并发覆盖检测的第一半）
BEFORE="$(ssh -o ConnectTimeout=10 "$HOST" "readlink -f '$LINK' 2>/dev/null || echo none")"
say "出发时线上：$(basename "$BEFORE")"

# ── 回滚
if [ "${1:-}" = "--rollback" ]; then
    PREV="$(ssh "$HOST" "tail -n 2 '$REMOTE_DIR/deploy.log' 2>/dev/null | head -n 1 | awk '{print \$3}'")"
    [ -n "$PREV" ] || die "deploy.log 里没有上一版可回退"
    ssh "$HOST" "test -f '$REMOTE_DIR/$PREV'" || die "上一版的包已经不在了：$PREV"
    say "回滚到 $PREV"
    ssh "$HOST" "sudo ln -sfn '$PREV' '$LINK' && sudo systemctl restart '$SERVICE'"
    wait_healthy || die "回滚后没等到 health=200"
    ok "已回滚到 $PREV"; exit 0
fi

# ── 部署锁（mkdir 原子）
LOCKDIR="$REMOTE_DIR/.deploy.lock"
LOCK_OWNER="$(whoami)@$(hostname -s) pid=$$ 开始于 $(date '+%F %T')"
if ! ssh "$HOST" "mkdir '$LOCKDIR' 2>/dev/null"; then
    HOLDER="$(ssh "$HOST" "cat '$LOCKDIR/owner' 2>/dev/null" || true)"
    die "有人正在部署：${HOLDER:-（未知）}
    确认那个进程死了之后：ssh $HOST \"rm -rf '$LOCKDIR'\""
fi
ssh "$HOST" "echo '$LOCK_OWNER' > '$LOCKDIR/owner'" || true
ok "已拿到部署锁"

WT=""
release_lock() { ssh "$HOST" "rm -rf '$LOCKDIR'" >/dev/null 2>&1 || true; }
cleanup() {
    [ -n "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1 || true
    [ -n "${JAR_NAME:-}" ] && ssh "$HOST" "rm -f /tmp/$JAR_NAME" >/dev/null 2>&1 || true
    release_lock
}
trap cleanup EXIT

# ── 从干净 HEAD 副本构建（不在主工作区）
WT="$(mktemp -d)/deploy-head"
git worktree add -q --detach "$WT" HEAD
TS="$(date +%Y%m%d-%H%M)"
JAR_NAME="$APP-$TS-$HEAD_SHA.jar"
say "构建中（干净副本）…"
( cd "$WT/backend" && mvn clean package -pl "$MVN_MODULE" -am -DskipTests -q ) \
    || die "构建失败"
LOCAL_JAR="$WT/backend/$JAR_IN_REPO"
[ -f "$LOCAL_JAR" ] || die "构建完了却找不到 jar：$LOCAL_JAR"
ok "构建完成 $(du -h "$LOCAL_JAR" | cut -f1)"

# ── 传包并核对 MD5
say "上传 $JAR_NAME"
scp -q "$LOCAL_JAR" "$HOST:/tmp/$JAR_NAME"
LOCAL_MD5="$(md5 -q "$LOCAL_JAR" 2>/dev/null || md5sum "$LOCAL_JAR" | cut -d' ' -f1)"
REMOTE_MD5="$(ssh "$HOST" "md5sum /tmp/$JAR_NAME | cut -d' ' -f1")"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] || die "MD5 不一致（本地 $LOCAL_MD5 / 远端 $REMOTE_MD5）"
ok "MD5 一致 $LOCAL_MD5"

if [ "${DRY:-}" = "1" ]; then
    ok "DRY=1：到此为止"
    exit 0
fi

# ── 切换前再看一次线上（并发覆盖检测的第二半）
NOW="$(ssh "$HOST" "readlink -f '$LINK' 2>/dev/null || echo none")"
if [ "$NOW" != "$BEFORE" ]; then
    die "线上在我打包这段时间里被别人换过了：
       出发时 $(basename "$BEFORE")
       现在   $(basename "$NOW")
    先去问是谁推的，确认后重跑本脚本"
fi
ok "线上仍是出发时那一版，可以切"

ssh "$HOST" "sudo install -o root -g root -m 644 /tmp/$JAR_NAME '$REMOTE_DIR/$JAR_NAME' \
    && sudo ln -sfn '$JAR_NAME' '$LINK' \
    && rm -f /tmp/$JAR_NAME"
ok "软链已指向 $JAR_NAME"

ssh "$HOST" "printf '%s  %s  %s  %s\n' \"\$(date '+%F %T')\" '$JAR_NAME' '$HEAD_SHA' \"\$(whoami)\" \
    | sudo tee -a '$REMOTE_DIR/deploy.log' >/dev/null" || true

# ── 重启并守 health
say "重启 $SERVICE"
ssh "$HOST" "sudo systemctl restart '$SERVICE'"

if wait_healthy; then
    ok "起来了"
else
    die "没等到 health=200。看日志：
    ssh $HOST 'sudo journalctl -u $SERVICE -n 80 --no-pager'
    要回滚：ssh $HOST \"sudo ln -sfn $(basename "$BEFORE") '$LINK' && sudo systemctl restart '$SERVICE'\""
fi

# ── 保留最近 5 个 + 当前
say "清理旧版本包（保留最近 5 + 当前）"
ssh "$HOST" "cd '$REMOTE_DIR' || exit 0
    cur=\$(readlink '$LINK_NAME' 2>/dev/null)
    n=0
    for f in \$(ls -1t '$APP'-*.jar 2>/dev/null | tail -n +6); do
        [ \"\$f\" = \"\$cur\" ] && continue
        sudo rm -f -- \"\$f\" && n=\$((n+1))
    done
    echo \"  删了 \$n 个，现存 \$(ls -1 '$APP'-*.jar 2>/dev/null | wc -l) 个\"" \
    || warn "旧包清理没跑成（不影响这次上线）"

printf '\n\033[32m上线完成\033[0m  %s  ←  %s %s\n' "$JAR_NAME" "$HEAD_SHA" "$HEAD_MSG"
printf '回滚：ssh %s "sudo ln -sfn %s %s && sudo systemctl restart %s"\n' \
    "$HOST" "$(basename "$BEFORE")" "$LINK" "$SERVICE"
