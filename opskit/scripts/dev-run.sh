#!/usr/bin/env bash
# 本地联调：以 local 配置档起 OpsKit（内存库，每次启动重建并写种子；端口 8090）。
#   opskit/scripts/dev-run.sh             # 构建后前台运行，Ctrl-C 停
#   OPSKIT_PORT=8091 opskit/scripts/dev-run.sh
# 起来后：opskit/scripts/dev-token.sh seed   看种子清单；dev-token.sh alice   签 alice 的令牌
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" || ! "$("$JAVA_HOME/bin/java" -version 2>&1)" =~ \"21\. ]]; then
  if command -v /usr/libexec/java_home >/dev/null && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  elif [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  else
    echo "需要 JDK 21，请设置 JAVA_HOME" >&2; exit 1
  fi
fi
mvn -q -pl opskit-svc -am -DskipTests package
exec "$JAVA_HOME/bin/java" -jar opskit-svc/target/opskit-svc-*.jar --spring.profiles.active=local "$@"
