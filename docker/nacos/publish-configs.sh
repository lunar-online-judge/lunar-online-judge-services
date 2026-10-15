#!/usr/bin/env bash
# 把 docker/nacos/config/*.yml 发布到 Nacos —— 配置内容的 git 真源 → Nacos 的推送脚本。
# 用官方 nacos-client（不依赖控制台鉴权，也不会有 HTTP API 版本差异问题）。
#
# 前提（首次或依赖变更后）：
#   mvn -DskipTests install
#   mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt -pl services/auth-service
#
# 用法：JAVA_HOME=/path/to/jdk-21 bash docker/nacos/publish-configs.sh
# 可用环境变量覆盖：NACOS_SERVER_ADDR（默认 localhost:8848）、NACOS_CONFIG_NAMESPACE（默认空 = public）
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

: "${JAVA_HOME:?请先把 JAVA_HOME 指向 JDK 21（本项目编译基线）}"
SERVER_ADDR="${NACOS_SERVER_ADDR:-localhost:8848}"
NAMESPACE="${NACOS_CONFIG_NAMESPACE:-}"
CLASSPATH_FILE="services/auth-service/target/cp.txt"

if [ ! -f "$CLASSPATH_FILE" ]; then
  echo "缺少 $CLASSPATH_FILE，请先执行：" >&2
  echo "  mvn -DskipTests install" >&2
  echo "  mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt -pl services/auth-service" >&2
  exit 1
fi

echo "发布 docker/nacos/config/*.yml 到 $SERVER_ADDR（namespace='$NAMESPACE'，空表示 public）"
exec "$JAVA_HOME/bin/java" -cp "$(cat "$CLASSPATH_FILE")" \
  docker/nacos/NacosConfigPublisher.java "$SERVER_ADDR" "$NAMESPACE" docker/nacos/config
