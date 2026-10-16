#!/bin/bash
# ai-service / analytics-service 简化后的验收
#   - 保留的 2 个能力端点必须可用（作业入队成功）
#   - 删除的端点必须 404（避免"代码删了路由还在"）
#   - analytics 的读模型端点仍可用
# 注意：脚本内容保持 ASCII（Git Bash 传中文正文给 curl 会破坏 JSON）

AUTH=http://localhost:8081
CONTEST=http://localhost:8082
AI=http://localhost:8089
ANALYTICS=http://localhost:8088

TS=$(date +%s)
PASS=0; FAIL=0
step() { echo; echo "=== $* ==="; }
ok()   { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  PASS  $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1  期望[$3] 实际[$2]"; fi; }
jget() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
code() { echo "$1" | tail -1; }

step "0. 登录 + 建一场考试（AI 端点的范围校验要求考试归属）"
BODY=$(curl -s -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
ADMIN=$(jget "$BODY" accessToken)
ok "管理员登录" "$([ -n "$ADMIN" ] && echo yes || echo no)" "yes"
START=$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)
BODY=$(curl -s -X POST "$CONTEST/api/v1/contests" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"ai-check-$TS\",\"startAt\":\"$START\",\"endAt\":\"$END\"}")
CID=$(jget "$BODY" id)
ok "考试创建" "$([ -n "$CID" ] && echo yes || echo no)" "yes"

step "1. 保留能力：代码评审（入队）"
CODE=$(curl -s -o /tmp/ai1.json -w '%{http_code}' -X POST "$AI/api/v1/ai/jobs/code-review" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"contestId\":\"$CID\",\"submissionId\":\"00000000-0000-0000-0000-0000000000aa\"}")
ok "POST /jobs/code-review" "$CODE" "200"
JOB1=$(jget "$(cat /tmp/ai1.json)" jobId)
ok "  返回作业号" "$([ -n "$JOB1" ] && echo yes || echo no)" "yes"

step "2. 保留能力：出题草稿（入队）"
CODE=$(curl -s -o /tmp/ai2.json -w '%{http_code}' -X POST "$AI/api/v1/ai/jobs/problem-draft" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"contestId\":\"$CID\",\"requirement\":\"sum of two integers\",\"difficulty\":\"EASY\",\"languageHint\":\"cpp\"}")
ok "POST /jobs/problem-draft" "$CODE" "200"
JOB2=$(jget "$(cat /tmp/ai2.json)" jobId)
ok "  返回作业号" "$([ -n "$JOB2" ] && echo yes || echo no)" "yes"

step "3. 已删除能力：端点必须 404"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$AI/api/v1/ai/jobs/risk-assessment" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"contestId\":\"$CID\",\"studentId\":\"00000000-0000-0000-0000-0000000000bb\"}")
case "$CODE" in 404|405) R=ok;; *) R=bad;; esac
ok "POST /jobs/risk-assessment（已删，404/405 均可）" "$R" "ok"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$AI/api/v1/ai/jobs/similarity-scan" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"contestId\":\"$CID\",\"problemId\":\"00000000-0000-0000-0000-0000000000cc\"}")
case "$CODE" in 404|405) R=ok;; *) R=bad;; esac
ok "POST /jobs/similarity-scan（已删，404/405 均可）" "$R" "ok"

step "4. 查询类端点仍可用"
CODE=$(curl -s -o /tmp/ai3.json -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$AI/api/v1/ai/jobs?page=1&pageSize=5")
ok "GET /jobs" "$CODE" "200"
CODE=$(curl -s -o /tmp/ai4.json -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$AI/api/v1/ai/findings?page=1&pageSize=5")
ok "GET /findings" "$CODE" "200"
CODE=$(curl -s -o /tmp/ai5.json -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$AI/api/v1/ai/provider")
ok "GET /provider" "$CODE" "200"
ok "  provider 不含 embedding 字段" "$(grep -c embedding /tmp/ai5.json)" "0"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$AI/api/v1/ai/drafts?page=1&pageSize=5")
ok "GET /drafts" "$CODE" "200"

step "5. 权限基线（考生不能触发计费调用）"
BODY=$(curl -s -X POST "$CONTEST/api/v1/contests/$CID/students" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"studentNo\":\"AI$TS\",\"username\":\"aistu_$TS\",\"displayName\":\"AiStu\",\"initialPassword\":\"init-pass-123\"}")
BODY=$(curl -s -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"aistu_$TS\",\"password\":\"init-pass-123\"}")
STU=$(jget "$BODY" accessToken)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $STU" "$AI/api/v1/ai/jobs?page=1&pageSize=5")
ok "考生访问 AI 端点 → 403" "$CODE" "403"

step "6. analytics 读模型仍可用"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" \
  "$ANALYTICS/api/v1/analytics/contests/$CID/scoreboard?page=1&pageSize=5")
ok "成绩榜" "$CODE" "200"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" \
  "$ANALYTICS/api/v1/analytics/contests/$CID/risk/summary")
ok "风险汇总" "$CODE" "200"

echo
echo "AI 验收：通过 $PASS   失败 $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
