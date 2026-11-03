#!/usr/bin/env bash
# 从各服务的 OpenAPI 文档汇总出端点清单，写入 docs/api-generated.md。
#
# 为什么要有这个脚本：docs/api.md 是人工维护的 365 行文档，最容易与代码漂移
# （审计发现至少 5 处自相矛盾）。端点/状态这类**可从代码推导**的信息不该手写。
# 约定：本脚本生成 docs/api-generated.md（只含方法/路径/摘要/所需角色）；
# 人工维护的 docs/api.md 保留"行为说明、错误码、未实现清单"这类无法推导的内容。
set -uo pipefail

OUT="docs/api-generated.md"
BASE="${BASE:-http://localhost}"

declare -A SERVICES=(
  [auth]=8081 [contest]=8082 [problem]=8083 [submission]=8084
  [anti-cheat]=8086 [analytics]=8098
)

command -v jq >/dev/null 2>&1 || { echo "需要 jq"; exit 1; }

{
  echo "# 端点清单（自动生成，勿手改）"
  echo
  echo "> 由 \`scripts/gen-api-doc.sh\` 从各服务 \`/v3/api-docs\` 生成。"
  echo "> 行为说明、错误码、未实现清单请看 \`docs/api.md\`（人工维护）。"
  echo
} > "$OUT"

for service in "${!SERVICES[@]}"; do
  port="${SERVICES[$service]}"
  echo "## $service (:$port)" >> "$OUT"
  echo >> "$OUT"

  spec=$(curl -sf --max-time 5 "$BASE:$port/v3/api-docs" 2>/dev/null)
  if [ -z "$spec" ]; then
    echo "_服务未运行或未暴露 OpenAPI（$BASE:$port/v3/api-docs）_" >> "$OUT"
    echo >> "$OUT"
    continue
  fi

  # 路径 -> 方法 -> 摘要；按路径排序输出成表格
  echo "$spec" | jq -r '
    .paths as $paths
    | $paths | to_entries[]
    | .key as $path
    | .value | to_entries[]
    | select(.key | test("^(get|post|put|patch|delete)$"))
    | "| \(.key | ascii_upcase) | `\($path)` | \(.value.summary // "-") |"
  ' | sort >> "$OUT"

  echo >> "$OUT"
done

echo "已生成 $OUT"
