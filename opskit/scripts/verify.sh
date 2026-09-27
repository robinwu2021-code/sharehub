#!/usr/bin/env bash
# OpsKit 本地 / CI 统一验证入口（21/07 O0-7）。
#   scripts/verify.sh                      # 全量：构建 + 四库测试 + 架构测试
#   scripts/verify.sh -pl commons          # 透传任意 mvn 参数（默认目标 verify）
#   OPSKIT_TEST_DATABASES=h2 scripts/verify.sh   # 只跑 H2 快速档（不作为通过依据）
set -euo pipefail
cd "$(dirname "$0")/.."

# neargo-parent 的 enforcer 锁 JDK [21,22)；本机默认 JDK 往往不是 21。
if [[ -z "${JAVA_HOME:-}" || ! "$("$JAVA_HOME/bin/java" -version 2>&1)" =~ \"21\. ]]; then
  if command -v /usr/libexec/java_home >/dev/null && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  elif [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  else
    echo "需要 JDK 21，请设置 JAVA_HOME" >&2; exit 1
  fi
  export JAVA_HOME
fi

# Testcontainers 不读 docker context（colima / OrbStack / Rancher 都靠 context 指 socket），
# 从当前 context 推出 DOCKER_HOST；Ryuk 挂载的是虚拟机内的 socket 路径。
if [[ -z "${DOCKER_HOST:-}" ]] && command -v docker >/dev/null; then
  host="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
  [[ -n "$host" ]] && export DOCKER_HOST="$host"
fi
if [[ "${DOCKER_HOST:-}" == unix://*colima* ]]; then
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}"
fi

args=("$@")
# 没给目标（生命周期阶段或 插件:目标）时默认 verify。注意 -Dtest=… 之类的参数不算目标。
has_goal=false
for a in "$@"; do
  case "$a" in
    -*) ;;
    clean|validate|compile|test-compile|test|package|verify|install|deploy) has_goal=true ;;
    *:*) has_goal=true ;;
  esac
done
$has_goal || args+=(verify)
db_prop=()
[[ -n "${OPSKIT_TEST_DATABASES:-}" ]] && db_prop=("-Dopskit.test.databases=${OPSKIT_TEST_DATABASES}")

echo "JAVA_HOME=$JAVA_HOME  DOCKER_HOST=${DOCKER_HOST:-<unset>}" >&2
exec mvn -B ${db_prop[@]+"${db_prop[@]}"} "${args[@]}"
