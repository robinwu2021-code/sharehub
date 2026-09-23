#!/usr/bin/env bash
# 发布前自检 —— 把 README 里那几条「必查」从「要有人记得跑」变成「跑一条命令」。
#
# ## 为什么要有
#
# README 的必查项一直都在，问题是它们散在文档各处、靠人记得。
# 本仓已经为同一件事做过一次改造：前后端漂移检查本来挂在 `npm run check:drift` 下
# （也就是要有人记得去跑），实际没人跑，于是「漂移可被发现」退化成
# 「漂移可被发现，如果你已经知道它在那儿」。搬进测试之后才真的跑到。
#
# 这个脚本是同一个思路：**发布前跑一次，缺项就拦住，不靠自觉。**
#
# ## 用法
#
#   deploy/tencent/preflight.sh              # 对生产做全部检查
#   deploy/tencent/preflight.sh --host=xxx   # 换一台机器
#   deploy/tencent/preflight.sh --skip-ssh   # 只做本地检查（迁移号连续性等）
#
# 退出码非 0 = 有必查项没过，**不要发布**。
set -uo pipefail

HOST="${SHAREHUB_DEPLOY_HOST:-soukmind-tx}"
ENV_FILE="/data/app/powerbank/sharehub-app/sharehub-app.env"
DB_NAME="powerbank"
SKIP_SSH=0

for arg in "$@"; do
  case "$arg" in
    --host=*)   HOST="${arg#*=}" ;;
    --skip-ssh) SKIP_SSH=1 ;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

# 一律用 sed -E（ERE）：BSD sed（开发机 macOS）不认 \+，
# 用基本正则写的话在开发机上静默匹配不到 —— 表现是「找不到任何迁移文件」，
# 而服务器（GNU sed）上却正常，最容易被当成环境问题放过去。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/backend/sharehub-app/src/main/resources/db/migration"

FAIL=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

remote() { ssh -o BatchMode=yes -o ConnectTimeout=8 "$HOST" "$1" 2>/dev/null; }

# ─────────────────────────── 本地：迁移号 ───────────────────────────
head_ "迁移文件（本地）"

MIN_VER=$(ls "$MIGRATION_DIR" 2>/dev/null | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | head -1)
MAX_VER=$(ls "$MIGRATION_DIR" 2>/dev/null | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | tail -1)
if [ -z "$MIN_VER" ]; then
  fail "找不到任何迁移文件（$MIGRATION_DIR）"
else
  pass "迁移号范围 V${MIN_VER}..V${MAX_VER}"
fi

# 重号：两个会话各自占号段时最容易撞，Flyway 会直接拒绝启动
DUP=$(ls "$MIGRATION_DIR" | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | uniq -d)
if [ -n "$DUP" ]; then
  fail "迁移号重复：$(echo "$DUP" | tr '\n' ' ') —— Flyway 会拒绝启动"
else
  pass "迁移号无重复"
fi

# ─────────────────────────── 远端 ───────────────────────────
if [ "$SKIP_SSH" = "1" ]; then
  head_ "远端检查已跳过（--skip-ssh）"
  warn "env 与迁移顺序两类必查项**没有验证**，不要据此发布"
else
  head_ "连通性"
  if remote 'echo ok' | grep -q ok; then
    pass "可 ssh 到 $HOST"
  else
    fail "连不上 $HOST —— 后面的检查无法进行（要么修连接，要么 --skip-ssh 明确跳过）"
    printf '\n\033[31m%d 项未通过，不要发布。\033[0m\n' "$((FAIL))"
    exit 1
  fi

  head_ "环境变量（$ENV_FILE）"

  # ① pepper：为空 → 进程直接退出。比 ADMIN_PASSWORD 那条更硬
  N=$(remote "sudo grep -cE '^SHAREHUB_IDENTITY_PEPPER=.{32,}' $ENV_FILE" || echo 0)
  if [ "${N:-0}" -ge 1 ]; then
    pass "SHAREHUB_IDENTITY_PEPPER 已配且 ≥32 字符"
  else
    fail "SHAREHUB_IDENTITY_PEPPER 未配或过短 —— **应用起不来**（IdentifierHasher fail-closed）"
    printf '      生成：openssl rand -base64 48   然后写入 %s（600，不进 git）\n' "$ENV_FILE"
  fi

  # ② 管理员口令：为空 → 起得来但没人能登录
  N=$(remote "sudo grep -c '^SHAREHUB_ADMIN_PASSWORD=.\\+' $ENV_FILE" || echo 0)
  if [ "${N:-0}" -ge 1 ]; then
    pass "SHAREHUB_ADMIN_PASSWORD 非空"
  else
    fail "SHAREHUB_ADMIN_PASSWORD 为空 —— 运营端彻底登不进（fail-closed 的预期行为）"
  fi

  # ③ dev-mode：开着 = 固定验证码 000000 + OTP 明文回传 + 免密登录运营端
  N=$(remote "sudo grep -c '^SHAREHUB_DEV_MODE_ENABLED=true' $ENV_FILE" || echo 0)
  if [ "${N:-0}" -eq 0 ]; then
    pass "SHAREHUB_DEV_MODE_ENABLED 未开"
  else
    fail "SHAREHUB_DEV_MODE_ENABLED=true —— 线上任何人可登录任意手机号，**绝不可发布**"
  fi

  # ④ CORS：不配的话浏览器登录失败而 curl 测试通过，最难查的一类
  if remote "sudo grep -q '^SHAREHUB_CORS_ALLOWED_ORIGINS=.*https://' $ENV_FILE"; then
    pass "SHAREHUB_CORS_ALLOWED_ORIGINS 含生产域名"
  else
    fail "SHAREHUB_CORS_ALLOWED_ORIGINS 未含 https 域名 —— 浏览器 POST 会 403，而 curl 测试通过"
  fi

  # ⑤ env 里不许有 IP 字面量（换 IP 会炸）
  IPS=$(remote "sudo grep -hoE '(jdbc:mysql://|http://)[0-9]{1,3}(\\.[0-9]{1,3}){3}' $ENV_FILE")
  if [ -z "$IPS" ]; then
    pass "env 中无 IP 字面量"
  else
    fail "env 中有 IP 字面量：$(echo "$IPS" | tr '\n' ' ') —— 未来换 IP 会炸"
  fi

  head_ "迁移号顺序（这次带上去的最小号 vs 现网已应用的最高版本）"

  # Flyway 没开 out-of-order，版本号低于现网最高版本的迁移再也进不去，且会让启动失败。
  # 多会话并行各占号段时，**谁先部署谁的号就成了基线** —— 分批部署最容易踩。
  APPLIED=$(remote "sudo mysql -N -e \"SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM ${DB_NAME}.flyway_schema_history\"")
  if [ -z "$APPLIED" ]; then
    warn "读不到 flyway_schema_history（库未建或无权限）—— 首次部署可忽略"
  elif [ -z "$MIN_VER" ]; then
    warn "本地无迁移文件，跳过比对"
  else
    # 只比「尚未应用的那些」里的最小号
    UNAPPLIED_MIN=$(ls "$MIGRATION_DIR" | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n \
      | awk -v a="$APPLIED" '$1 > a {print; exit}')
    if [ -z "$UNAPPLIED_MIN" ]; then
      pass "现网已应用到 V${APPLIED}，本次无新迁移"
    else
      LOWER=$(ls "$MIGRATION_DIR" | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n \
        | awk -v a="$APPLIED" '$1 <= a {print}' | wc -l | tr -d ' ')
      TOTAL_APPLIED=$(remote "sudo mysql -N -e \"SELECT COUNT(*) FROM ${DB_NAME}.flyway_schema_history WHERE success=1\"")
      if [ "${LOWER:-0}" -gt "${TOTAL_APPLIED:-0}" ]; then
        fail "有迁移的版本号 ≤ 现网最高版本 V${APPLIED} 却尚未应用 —— Flyway 会拒绝启动"
        printf '      要么等那批一起合并后整体部署，要么重新编号（仅在未部署到任何环境时才安全）\n'
      else
        pass "现网 V${APPLIED}，本次从 V${UNAPPLIED_MIN} 起顺序进入"
      fi
    fi
  fi
fi

# ─────────────────────────── 结论 ───────────────────────────
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m全部必查项通过。\033[0m\n'
  exit 0
fi
printf '\033[31m%d 项未通过，不要发布。\033[0m\n' "$FAIL"
exit 1
