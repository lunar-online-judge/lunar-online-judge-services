#!/bin/bash
# Lunar OJ 流程测试：一次完整的"真实考试"业务流（区别于 api-full-test.sh 的逐端点检查）
#
# 串联：建题 → 建考试/审核/发布 → 锁定题目 → 加学生 → 学生进会话 → 公告(送达/确认)
#       → 随机分配 → 提交(分配的题/未分配的题) → 真实判题 → 成绩 → 会话终止
#
# 用法：bash scripts/exam-flow-test.sh

AUTH=http://localhost:8081
CONTEST=http://localhost:8082
PROBLEM=http://localhost:8083
SUBMISSION=http://localhost:8084
ANTICHEAT=http://localhost:8086

TS=$(date +%s)
PASS=0; FAIL=0
step() { echo; echo "=== $* ==="; }
ok()   { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  PASS  $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1  期望[$3] 实际[$2]"; fi; }
jget() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
jnum() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2; }

step "1. 管理员登录"
BODY=$(curl -s -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
ADMIN=$(jget "$BODY" accessToken)
ok "管理员登录拿到令牌" "$([ -n "$ADMIN" ] && echo yes || echo no)" "yes"

step "2. 建两道题并发布"
PROB_IDS=(); VER_IDS=()
for N in 1 2; do
  BODY=$(curl -s -X POST "$PROBLEM/api/v1/problems" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"title\":\"flow-$N-$TS\",\"statement\":\"read two integers and print sum\",\"sampleInput\":\"1 2\",\"sampleOutput\":\"3\",\"timeLimitMs\":2000,\"memoryLimitKb\":262144,\"allowedLanguages\":\"cpp\",\"testcases\":[{\"name\":\"case1\",\"inputContent\":\"1 2\",\"expectedOutput\":\"3\"}]}")
  PID=$(jget "$BODY" problemId)
  BODY=$(curl -s "$PROBLEM/api/v1/problems/$PID/versions" -H "Authorization: Bearer $ADMIN")
  VID=$(jget "$BODY" versionId)
  curl -s -o /dev/null -X POST "$PROBLEM/api/v1/problems/$PID/versions/$VID/publish" -H "Authorization: Bearer $ADMIN"
  PROB_IDS+=("$PID"); VER_IDS+=("$VID")
  ok "题目 $N 建好并发布" "$([ -n "$PID" ] && [ -n "$VID" ] && echo yes || echo no)" "yes"
done

step "3. 建考试 → 提交审核 → 管理员发布"
START=$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)
BODY=$(curl -s -X POST "$CONTEST/api/v1/contests" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"flow-exam-$TS\",\"startAt\":\"$START\",\"endAt\":\"$END\"}")
CID=$(jget "$BODY" id)
ok "考试创建（DRAFT）" "$([ -n "$CID" ] && echo yes || echo no)" "yes"
curl -s -o /dev/null -X POST "$CONTEST/api/v1/contests/$CID/review" -H "Authorization: Bearer $ADMIN"
CODE=$(curl -s -o /tmp/flow_pub.json -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/publish" -H "Authorization: Bearer $ADMIN")
ok "发布考试" "$CODE" "200"

step "4. 锁定题目进考试（BE-04）"
for I in 0 1; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/problems" -H "Authorization: Bearer $ADMIN" \
    -H 'Content-Type: application/json' -d "{\"problemId\":\"${PROB_IDS[$I]}\",\"problemVersionId\":\"${VER_IDS[$I]}\"}")
  ok "锁定题目 $((I+1))" "$CODE" "200"
done

step "5. 加两名学生（auth 建号 + 报名）"
STU_TOKENS=(); STU_NAMES=()
for N in 1 2; do
  U="flowstu${N}_$TS"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/students" -H "Authorization: Bearer $ADMIN" \
    -H 'Content-Type: application/json' -d "{\"studentNo\":\"FLOW$TS$N\",\"username\":\"$U\",\"displayName\":\"Flow$N\",\"initialPassword\":\"init-pass-123\"}")
  ok "加入学生 $N" "$CODE" "200"
  BODY=$(curl -s -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"init-pass-123\"}")
  STU_TOKENS+=("$(jget "$BODY" accessToken)"); STU_NAMES+=("$U")
  ok "学生 $N 登录" "$([ -n "$(jget "$BODY" accessToken)" ] && echo yes || echo no)" "yes"
done

step "6. 学生进入考试会话"
CODE=$(curl -s -o /tmp/flow_sess.json -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/session" -H "Authorization: Bearer ${STU_TOKENS[0]}")
ok "进入会话" "$CODE" "200"
ok "  会话状态 ACTIVE" "$(jget "$(cat /tmp/flow_sess.json)" status)" "ACTIVE"

step "7. 公告：发布(需确认) → 学生拉取(送达) → 确认"
BODY=$(curl -s -X POST "$CONTEST/api/v1/contests/$CID/announcements" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"content":"please confirm you have read the exam notice","important":true,"requireConfirmation":true}')
AID=$(jget "$BODY" announcementId)
ok "发布需确认公告" "$([ -n "$AID" ] && echo yes || echo no)" "yes"
BODY=$(curl -s "$CONTEST/api/v1/contests/$CID/announcements" -H "Authorization: Bearer ${STU_TOKENS[0]}")
ok "学生拉取(送达) recorded" "$([ -n "$(jget "$BODY" deliveredAt)" ] && echo true || echo false)" "true"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/announcements/$AID/confirm" -H "Authorization: Bearer ${STU_TOKENS[0]}")
ok "学生确认公告" "$CODE" "200"
BODY=$(curl -s "$CONTEST/api/v1/contests/$CID/announcements" -H "Authorization: Bearer ${STU_TOKENS[0]}")
ok "确认时间已记录" "$([ -n "$(jget "$BODY" confirmedAt)" ] && echo true || echo false)" "true"

step "8. 随机题目分配（每人 1 题）"
docker exec docker-postgres-1 psql -U lunar -d lunar -c "update contest_schema.contests set problems_per_student = 1 where id = '$CID';" >/dev/null 2>&1
BODY=$(curl -s -X POST "$CONTEST/api/v1/contests/$CID/assignments/generate" -H "Authorization: Bearer $ADMIN")
ok "生成分配（2 名学生各 1 题）" "$(jnum "$BODY" created)" "2"
BODY=$(curl -s "$CONTEST/api/v1/contests/$CID/assignments/me" -H "Authorization: Bearer ${STU_TOKENS[0]}")
ASSIGNED=$(jget "$BODY" problemId)
ok "学生看到自己的分配" "$([ -n "$ASSIGNED" ] && echo yes || echo no)" "yes"
OTHER=""
for P in "${PROB_IDS[@]}"; do [ "$P" != "$ASSIGNED" ] && OTHER="$P"; done

step "9. 提交：分配的题 → 真实判题；未分配的题 → 404"
CODE=$(curl -s -o /tmp/flow_sub.json -w '%{http_code}' -X POST "$SUBMISSION/api/v1/submissions" -H "Authorization: Bearer ${STU_TOKENS[0]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: flow-$TS-1" \
  -d "{\"contestId\":\"$CID\",\"problemId\":\"$ASSIGNED\",\"language\":\"cpp\",\"sourceCode\":\"#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;return 0;}\"}")
ok "提交分配的题" "$CODE" "200"
SUB=$(jget "$(cat /tmp/flow_sub.json)" submissionId)
CODE=$(curl -s -o /tmp/flow_sub2.json -w '%{http_code}' -X POST "$SUBMISSION/api/v1/submissions" -H "Authorization: Bearer ${STU_TOKENS[0]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: flow-$TS-2" \
  -d "{\"contestId\":\"$CID\",\"problemId\":\"$OTHER\",\"language\":\"cpp\",\"sourceCode\":\"int main(){return 0;}\"}")
ok "提交未分配的题被拒" "$CODE" "404"
ok "  错误码" "$(jget "$(cat /tmp/flow_sub2.json)" code)" "SUBMISSION_PROBLEM_NOT_IN_CONTEST"

step "10. 等待真实判题（go-judge）"
for I in $(seq 1 20); do
  BODY=$(curl -s "$SUBMISSION/api/v1/submissions/$SUB" -H "Authorization: Bearer ${STU_TOKENS[0]}")
  ST=$(jget "$BODY" status)
  [ "$ST" != "QUEUED" ] && [ "$ST" != "JUDGING" ] && break
  sleep 2
done
ok "判题出终态（ACCEPTED）" "$ST" "ACCEPTED"
ok "  得分为满分" "$(jnum "$BODY" score)" "100"

step "11. 会话终止（监考动作）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CONTEST/api/v1/contests/$CID/students/$(jget "$(cat /tmp/flow_sess.json)" studentId)/session/terminate" -H "Authorization: Bearer $ADMIN")
echo "  （终止会话接口返回 $CODE；若路径不同则以 api-full-test 覆盖为准）"

echo
echo "流程测试：通过 $PASS   失败 $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
