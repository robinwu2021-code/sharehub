#!/usr/bin/env bash
# 构建 OpsKit 运行镜像（21/07 O4-4）：先用 JDK 21 打包，再把 jar 装进 deploy/Dockerfile。
#   scripts/build-image.sh                 # 标签 opskit:<pom 版本>，另打 opskit:latest
#   OPSKIT_IMAGE=registry.example/opskit scripts/build-image.sh
#   SKIP_PACKAGE=1 scripts/build-image.sh  # 已跑过 verify.sh，直接用 target 里的 jar
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${SKIP_PACKAGE:-}" ]]; then
  scripts/verify.sh -q -pl opskit-svc -am -DskipTests package
fi
jar=$(ls opskit-svc/target/opskit-svc-*.jar | grep -v -- '-plain' | head -1)
version=$(basename "$jar" .jar); version=${version#opskit-svc-}
image="${OPSKIT_IMAGE:-opskit}"
ctx=$(mktemp -d); trap 'rm -rf "$ctx"' EXIT
cp deploy/Dockerfile "$ctx/"; cp "$jar" "$ctx/opskit-svc.jar"
docker build -t "$image:$version" -t "$image:latest" "$ctx"
echo "已构建 $image:$version"
