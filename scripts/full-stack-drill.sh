#!/bin/bash
# Lunar OJ 全链路演练：按 docs/deployment.md「冒烟验证」逐条执行 + 一次完整考试场景
#
# 用法：bash scripts/full-stack-drill.sh
# 注意：脚本内容保持 ASCII（Git Bash 传中文正文给 curl 会破坏 JSON，见 docs/exam-flow-test.md）

EDGE=http://localhost
PASS=0; FAIL=0
step() { echo; echo "=== $* ==="; }
ok()   { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  PASS  $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1  期望[$3] 实际[$2]"; fi; }
note() { echo "  NOTE  $1"; }

step "0. 边缘代理自检（OpenResty 直接返回，不回源）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$EDGE/healthz")
ok "GET /healthz" "$CODE" "200"

step "0.1 边缘 → gateway 回源链路（非 502 即回源可达）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$EDGE/api/v1/auth/jwks")
ok "GET /api/v1/auth/jwks（经边缘）" "$CODE" "200"

step "0.2 评测沙箱可达"
VERSION=$(curl -s http://localhost:5050/version)
ok "go-judge /version 返回版本号" "$(echo "$VERSION" | grep -o '"buildVersion":"[^"]*"' | cut -d'"' -f4)" "v1.13.0"

step "0.3 服务注册（Nacos）"
SERVICES=$(curl -s "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20")
COUNT=$(echo "$SERVICES" | grep -o '"count":[0-9]*' | head -1 | cut -d: -f2)
ok "已注册服务数（8 个）" "$COUNT" "8"
echo "        已注册：$(echo "$SERVICES" | grep -o '"[a-z-]*-service"' | tr -d '"' | tr '\n' ' ')"
INSTANCE=$(curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=auth-service")
echo "        auth-service 实例：$(echo "$INSTANCE" | head -c 160)"

step "0.4 健康检查（8 个服务）"
UP=0
for P in 8080 8081 8082 8083 8084 8085 8086 8087; do
  H=$(curl -s -m 5 "http://localhost:$P/actuator/health")
  if echo "$H" | grep -q '"status":"UP"'; then UP=$((UP+1)); else echo "        $P -> $H"; fi
done
ok "8 个服务全部 UP" "$UP" "8"
CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/env)
ok "auth 的非 health 端点不可用（401）" "$CODE" "401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/env)
ok "gateway 的非 health 端点不可用（404）" "$CODE" "404"

step "1. 真实登录 + JWKS"
BODY=$(curl -s -X POST http://localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$BODY" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
ok "admin 登录拿到令牌" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"
JWKS=$(curl -s http://localhost:8081/api/v1/auth/jwks)
ok "JWKS 含签名密钥" "$(echo "$JWKS" | grep -o '"kid":"[^"]*"' | head -1 | cut -d'"' -f4)" "kid-current"

step "2. 无令牌访问受保护端点 → 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8084/api/v1/submissions)
ok "submission 无令牌" "$CODE" "401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$EDGE/api/v1/contests")
ok "经边缘无令牌" "$CODE" "401"

step "3. 请求追踪（X-Request-Id 回显）"
RID=$(curl -s -i -H 'X-Request-Id: drill-123' http://localhost:8081/api/v1/auth/jwks | grep -i '^x-request-id' | tr -d '\r' | awk '{print $2}')
ok "响应头回显 X-Request-Id" "$RID" "drill-123"
RID2=$(curl -s -i http://localhost:8081/api/v1/auth/jwks | grep -i '^x-request-id' | tr -d '\r' | awk '{print $2}')
ok "缺失时自动生成（非空）" "$([ -n "$RID2" ] && echo yes || echo no)" "yes"

step "4. Swagger 放行"
CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/swagger-ui/index.html)
ok "auth 的 swagger-ui 可访问" "$CODE" "200"
CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/swagger-ui/index.html)
ok "judge 的 swagger-ui 未放行（401）" "$CODE" "401"

step "5. 完整考试场景（复用流程测试）"
if bash scripts/exam-flow-test.sh > /tmp/drill_flow.log 2>&1; then
  ok "流程测试全部通过" "0" "0"
  tail -1 /tmp/drill_flow.log | sed 's/^/        /'
else
  FAIL=$((FAIL+1)); echo "  FAIL  流程测试未通过，末尾日志："; tail -5 /tmp/drill_flow.log | sed 's/^/        /'
fi

echo
echo "全链路演练：通过 $PASS   失败 $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
