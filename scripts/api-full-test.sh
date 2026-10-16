#!/bin/bash
# Lunar OJ 全量 API 测试：覆盖 8 个服务 + 边缘/网关 + 基础设施
# 用法：EXPECTED_VERDICT=SYSTEM_ERROR bash logs/api-full-test.sh
AUTH=http://localhost:8081
CONTEST=http://localhost:8082
PROBLEM=http://localhost:8083
SUBMISSION=http://localhost:8084
JUDGE=http://localhost:8085
ANTICHEAT=http://localhost:8086
AUDIT=http://localhost:8087
GATEWAY=http://localhost:8080
EDGE=http://localhost
EXPECTED_VERDICT=${EXPECTED_VERDICT:-ACCEPTED}

TS=$(date +%s)
PASS=0; FAIL=0
FAILURES=""

jget() {
  printf '%s' "$1" | python -c "
import sys, json
sys.stdout.reconfigure(encoding='utf-8', newline=chr(10), errors='replace')
raw = sys.stdin.buffer.read()
try:
    d = json.loads(raw.decode('utf-8'))
except Exception:
    print(''); sys.exit()
for k in filter(None, sys.argv[1].split('.')):
    try:
        d = d[int(k)] if isinstance(d, list) else d[k]
    except Exception:
        d = None; break
print('true' if d is True else 'false' if d is False else '' if d is None else d)
" "$2"
}

jlen() {
  printf '%s' "$1" | python -c "
import sys, json
raw = sys.stdin.buffer.read()
try:
    d = json.loads(raw.decode('utf-8'))
except Exception:
    print(0); sys.exit()
for k in filter(None, sys.argv[1].split('.')):
    try:
        d = d[int(k)] if isinstance(d, list) else d[k]
    except Exception:
        d = None; break
print(0 if d is None else len(d))
" "$2"
}

# 请求体写入临时文件后 --data-binary 提交：Windows 原生 curl 会按 ANSI 码页
# 转码命令行参数，中文直接写进 -d 会变成非法 UTF-8（服务端 400）
api() {
  local args=(-s -m 40 -X "$1" -w $'\n%{http_code}')
  [ -n "$3" ] && args+=(-H "Authorization: Bearer $3")
  local bodyfile=""
  if [ -n "$4" ]; then
    bodyfile=$(mktemp)
    printf '%s' "$4" > "$bodyfile"
    args+=(-H "Content-Type: application/json; charset=utf-8" --data-binary "@$bodyfile")
  fi
  [ -n "${5:-}" ] && args+=(-H "Idempotency-Key: $5")
  local out; out=$(curl "${args[@]}" "$2")
  CODE="${out##*$'\n'}"; BODY="${out%$'\n'*}"
  [ -n "$bodyfile" ] && rm -f "$bodyfile"
}

# 依赖前置 id 的用例：id 为空时直接判定失败并终止，避免连锁产生一堆误导性失败
require_id() {
  if [ -z "$1" ]; then
    printf '\n  ABORT 前置 id 为空，后续用例无法继续：%s\n' "$2"
    printf '\n通过 %s   失败 %s（提前终止）\n' "$PASS" "$((FAIL+1))"
    exit 2
  fi
}

check() {
  if [ "$2" = "$3" ]; then
    PASS=$((PASS+1)); printf '  PASS  %-56s %s\n' "$1" "$3"
  else
    FAIL=$((FAIL+1))
    FAILURES="$FAILURES
  - $1 | 期望[$2] 实际[$3] | $(printf '%s' "$BODY" | head -c 220)"
    printf '  FAIL  %-56s 期望[%s] 实际[%s]\n        %s\n' "$1" "$2" "$3" "$(printf '%s' "$BODY" | head -c 220)"
  fi
}
section() { printf '\n=== %s ===\n' "$1"; }

section "0. 基础设施"
check "OpenResty /healthz"        200 "$(curl -s -o /dev/null -m 10 -w '%{http_code}' $EDGE/healthz)"
check "go-judge /version"         200 "$(curl -s -o /dev/null -m 10 -w '%{http_code}' http://localhost:5050/version)"
check "MinIO health/live"         200 "$(curl -s -o /dev/null -m 10 -w '%{http_code}' http://localhost:9000/minio/health/live)"
check "Nacos 控制台"              200 "$(curl -s -o /dev/null -m 10 -w '%{http_code}' http://localhost:8848/nacos/)"

GJ=$(curl -s -m 30 -X POST http://localhost:5050/run -H 'Content-Type: application/json' \
  -d '{"cmd":[{"args":["/bin/echo","hello"],"env":["PATH=/usr/local/bin:/usr/bin:/bin"],"files":[{"content":""},{"name":"stdout","max":102400},{"name":"stderr","max":102400}],"cpuLimit":1000000000,"memoryLimit":104857600,"procLimit":10}]}')
check "go-judge 沙箱执行 /bin/echo" Accepted "$(jget "$GJ" "0.status")"
check "  stdout 采集 = hello" "hello" "$(jget "$GJ" "0.files.stdout" | tr -d '\n')"

section "1. auth-service (8081)"
api POST "$AUTH/api/v1/auth/login" "" '{"username":"admin","password":"admin123"}'
check "登录 admin/admin123 → 200" 200 "$CODE"
ADMIN_TOKEN=$(jget "$BODY" "data.accessToken")
check "  role=ADMIN"              ADMIN "$(jget "$BODY" "data.role")"
check "  tokenType=Bearer"        Bearer "$(jget "$BODY" "data.tokenType")"
check "  含 expiresAt"            "true" "$([ -n "$(jget "$BODY" "data.expiresAt")" ] && echo true || echo false)"

api POST "$AUTH/api/v1/auth/login" "" '{"username":"admin","password":"wrong-password"}'
check "密码错误 → 401"            401 "$CODE"
check "  AUTH_INVALID_CREDENTIALS" AUTH_INVALID_CREDENTIALS "$(jget "$BODY" "error.code")"

api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"ghost_$TS\",\"password\":\"whatever\"}"
check "用户不存在 → 401（同码防枚举）" 401 "$CODE"
check "  与密码错误同码"          AUTH_INVALID_CREDENTIALS "$(jget "$BODY" "error.code")"

api POST "$AUTH/api/v1/auth/login" "" '{"username":"","password":""}'
check "空参数 → 400"              400 "$CODE"
check "  VALIDATION_FAILED"       VALIDATION_FAILED "$(jget "$BODY" "error.code")"

api GET "$AUTH/api/v1/auth/jwks" "" ""
check "JWKS → 200"                200 "$CODE"
check "  keys 非空"               "true" "$([ "$(jlen "$BODY" "keys")" -ge 1 ] && echo true || echo false)"
check "无 X-Request-Id → 自动生成" "true" "$(curl -s -D - -o /dev/null -m 10 $AUTH/api/v1/auth/jwks | grep -qi '^X-Request-Id:' && echo true || echo false)"
check "带 X-Request-Id → 原样回显" "my-trace-$TS" "$(curl -s -D - -o /dev/null -m 10 -H "X-Request-Id: my-trace-$TS" $AUTH/api/v1/auth/jwks | grep -i '^X-Request-Id:' | tr -d '\r' | awk '{print $2}')"

LOCKUSER="locktest_$TS"
for i in 1 2 3 4 5; do api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"$LOCKUSER\",\"password\":\"bad\"}" >/dev/null; done
api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"$LOCKUSER\",\"password\":\"bad\"}"
check "连续失败 5 次后 → 429"     429 "$CODE"
check "  AUTH_RATE_LIMITED"       AUTH_RATE_LIMITED "$(jget "$BODY" "error.code")"

api GET "$AUTH/api/v1/internal/students?studentNo=not-exist-$TS" "$ADMIN_TOKEN" ""
check "内部接口 查不存在学生 → 404" 404 "$CODE"
api GET "$AUTH/api/v1/internal/students?studentNo=x" "" ""
check "内部接口 无令牌 → 401"     401 "$CODE"

section "2. problem-service (8083)"
PROB_TITLE="A+B 全量测试 $TS"
PROB_BODY="{\"title\":\"$PROB_TITLE\",\"statement\":\"读入两个整数输出和\",\"sampleInput\":\"1 2\",\"sampleOutput\":\"3\",\"timeLimitMs\":2000,\"memoryLimitKb\":262144,\"outputLimitKb\":65536,\"allowedLanguages\":\"c,cpp,java,python\",\"testcases\":[{\"name\":\"01\",\"inputContent\":\"1 2\",\"expectedOutput\":\"3\"},{\"name\":\"02\",\"inputContent\":\"3 4\",\"expectedOutput\":\"7\"}]}"
api POST "$PROBLEM/api/v1/problems" "$ADMIN_TOKEN" "$PROB_BODY"
check "创建题目（含 2 测试点）→ 200" 200 "$CODE"
PROB_ID=$(jget "$BODY" "data.problemId")
require_id "$PROB_ID" "创建题目失败，PROB_ID 为空"

api GET "$PROBLEM/api/v1/problems/$PROB_ID/versions" "$ADMIN_TOKEN" ""
check "版本列表 → 200"            200 "$CODE"
VER1=$(jget "$BODY" "data.0.versionId")
check "  中文标题原样往返（UTF-8）" "$PROB_TITLE" "$(jget "$BODY" "data.0.title")"
require_id "$VER1" "版本列表为空，VER1 为空"

api GET "$PROBLEM/api/v1/problems?page=1&pageSize=5" "$ADMIN_TOKEN" ""
check "分页列表 → 200"            200 "$CODE"
check "  含 items"                "true" "$([ "$(jlen "$BODY" "data.items")" -ge 1 ] && echo true || echo false)"
check "  含 pageSize"             "true" "$([ -n "$(jget "$BODY" "data.pageSize")" ] && echo true || echo false)"

api GET "$PROBLEM/api/v1/problems/$PROB_ID" "$ADMIN_TOKEN" ""
check "未发布版本读详情 → 404"    404 "$CODE"
check "  PROBLEM_VERSION_NOT_FOUND" PROBLEM_VERSION_NOT_FOUND "$(jget "$BODY" "error.code")"

api POST "$PROBLEM/api/v1/problems/$PROB_ID/versions" "$ADMIN_TOKEN" "{\"baseVersionId\":\"$VER1\",\"title\":\"A+B v2 $TS\"}"
check "基于 v1 创建新版本 → 200"  200 "$CODE"
check "  版本号 = 2"              2 "$(jget "$BODY" "data.versionNo")"

api POST "$PROBLEM/api/v1/problems/$PROB_ID/versions/$VER1/publish" "$ADMIN_TOKEN" ""
check "发布 v1 → 200"             200 "$CODE"
check "  状态 PUBLISHED"          PUBLISHED "$(jget "$BODY" "data.status")"

api POST "$PROBLEM/api/v1/problems/$PROB_ID/versions/$VER1/publish" "$ADMIN_TOKEN" ""
check "重复发布 → 409"            409 "$CODE"
check "  PROBLEM_VERSION_IMMUTABLE" PROBLEM_VERSION_IMMUTABLE "$(jget "$BODY" "error.code")"

api GET "$PROBLEM/api/v1/problems/$PROB_ID" "$ADMIN_TOKEN" ""
check "发布后读详情 → 200"        200 "$CODE"
check "  返回已发布版本 v1"       "$VER1" "$(jget "$BODY" "data.versionId")"

api GET "$PROBLEM/api/v1/problems/$PROB_ID/versions/$VER1/testcases" "$ADMIN_TOKEN" ""
check "测试点查询 → 200"          200 "$CODE"
check "  测试点数量 = 2"          2 "$(jlen "$BODY" "data")"

api GET "$PROBLEM/api/v1/problems/00000000-0000-0000-0000-0000000000de" "$ADMIN_TOKEN" ""
check "不存在的题目 → 404"        404 "$CODE"
check "  PROBLEM_NOT_FOUND"       PROBLEM_NOT_FOUND "$(jget "$BODY" "error.code")"

section "3. contest-service (8082) 生命周期"
api POST "$CONTEST/api/v1/contests" "$ADMIN_TOKEN" "{\"name\":\"全量测试考试 $TS\",\"description\":\"API 测试\",\"startAt\":\"2026-12-01T02:00:00Z\",\"endAt\":\"2026-12-01T04:00:00Z\"}"
check "创建考试 → 200"            200 "$CODE"
CID=$(jget "$BODY" "data.id")
require_id "$CID" "创建考试失败，CID 为空"
check "  初始 DRAFT"              DRAFT "$(jget "$BODY" "data.status")"

api POST "$CONTEST/api/v1/contests/$CID/review" "$ADMIN_TOKEN" ""
check "提交审核 → 200"            200 "$CODE"
check "  状态 REVIEWING"          REVIEWING "$(jget "$BODY" "data.status")"

api POST "$CONTEST/api/v1/contests/$CID/publish" "$ADMIN_TOKEN" ""
check "管理员发布 → 200"          200 "$CODE"
check "  状态 PUBLISHED"          PUBLISHED "$(jget "$BODY" "data.status")"

api POST "$CONTEST/api/v1/contests/$CID/review" "$ADMIN_TOKEN" ""
check "已发布再提交审核 → 409"    409 "$CODE"
check "  CONTEST_INVALID_TRANSITION" CONTEST_INVALID_TRANSITION "$(jget "$BODY" "error.code")"

api GET "$CONTEST/api/v1/contests/$CID" "$ADMIN_TOKEN" ""
check "查询考试 → 200"            200 "$CODE"
api GET "$CONTEST/api/v1/contests?page=1&pageSize=5&status=PUBLISHED" "$ADMIN_TOKEN" ""
check "按状态分页 → 200"          200 "$CODE"
check "  含 items"                "true" "$([ "$(jlen "$BODY" "data.items")" -ge 1 ] && echo true || echo false)"
api GET "$CONTEST/api/v1/contests" "" ""
check "无令牌查询 → 401"          401 "$CODE"
api GET "$CONTEST/api/v1/contests/00000000-0000-0000-0000-0000000000de" "$ADMIN_TOKEN" ""
check "不存在的考试 → 404"        404 "$CODE"
check "  CONTEST_NOT_FOUND"       CONTEST_NOT_FOUND "$(jget "$BODY" "error.code")"

section "4. 学生名单（contest 编排 + auth 执行）"
SNO="9$TS"
api POST "$CONTEST/api/v1/contests/$CID/students" "$ADMIN_TOKEN" "{\"studentNo\":\"$SNO\",\"username\":\"student_$SNO\",\"displayName\":\"测试学生\",\"initialPassword\":\"init-pass-123\"}"
check "添加学生（auth 建号 + 报名）→ 200" 200 "$CODE"
SID=$(jget "$BODY" "data.studentId")
require_id "$SID" "添加学生失败，SID 为空"
check "  enrollmentStatus=ENROLLED" ENROLLED "$(jget "$BODY" "data.enrollmentStatus")"
check "  studentNo 快照正确" "$SNO" "$(jget "$BODY" "data.studentNo")"
# docs/api.md"添加学生" 的响应契约字段（本次修复点）
check "  【契约】docs/api.md 声明的 data.passwordChangeRequired=true" true "$(jget "$BODY" "data.passwordChangeRequired")"

api POST "$CONTEST/api/v1/contests/$CID/students" "$ADMIN_TOKEN" "{\"studentNo\":\"$SNO\",\"username\":\"student_$SNO\",\"displayName\":\"测试学生\",\"initialPassword\":\"init-pass-123\"}"
check "重复添加同一学号 → 409"    409 "$CODE"
check "  CONTEST_STUDENT_ALREADY_ENROLLED" CONTEST_STUDENT_ALREADY_ENROLLED "$(jget "$BODY" "error.code")"

api GET "$CONTEST/api/v1/contests/$CID/students?page=1&pageSize=20&keyword=$SNO" "$ADMIN_TOKEN" ""
check "名单分页 + keyword 过滤 → 200" 200 "$CODE"
check "  命中 1 条"               1 "$(jget "$BODY" "data.total")"

api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"student_$SNO\",\"password\":\"init-pass-123\"}"
check "学生用初始密码登录 → 200"  200 "$CODE"
check "  passwordChangeRequired=true" true "$(jget "$BODY" "data.passwordChangeRequired")"
check "  role=CANDIDATE"          CANDIDATE "$(jget "$BODY" "data.role")"

api POST "$CONTEST/api/v1/contests/$CID/students/$SID/password-reset" "$ADMIN_TOKEN" '{"temporaryPassword":"new-pass-456"}'
check "老师重置学生密码 → 200"    200 "$CODE"
api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"student_$SNO\",\"password\":\"init-pass-123\"}"
check "旧密码登录 → 401"          401 "$CODE"
api POST "$AUTH/api/v1/auth/login" "" "{\"username\":\"student_$SNO\",\"password\":\"new-pass-456\"}"
check "新密码登录 → 200"          200 "$CODE"
CAND_TOKEN=$(jget "$BODY" "data.accessToken")
require_id "$CAND_TOKEN" "学生登录失败，考生令牌为空"

api GET "$PROBLEM/api/v1/problems/$PROB_ID" "$CAND_TOKEN" ""
check "考生读题目详情 → 200"      200 "$CODE"
api POST "$PROBLEM/api/v1/problems" "$CAND_TOKEN" "$PROB_BODY"
check "考生创建题目 → 403"        403 "$CODE"
api GET "$CONTEST/api/v1/contests" "$CAND_TOKEN" ""
check "考生查询考试列表 → 200"    200 "$CODE"

section "5. 公告"
api POST "$CONTEST/api/v1/contests/$CID/announcements" "$ADMIN_TOKEN" "{\"content\":\"考试须知 $TS\",\"important\":true,\"requireConfirmation\":true}"
check "发布公告 → 200"            200 "$CODE"
AID=$(jget "$BODY" "data.announcementId")
require_id "$AID" "发布公告失败，AID 为空"
api GET "$CONTEST/api/v1/contests/$CID/announcements" "$ADMIN_TOKEN" ""
check "管理端公告列表 → 200"      200 "$CODE"
check "  命中 1 条"               1 "$(jlen "$BODY" "data")"

api GET "$CONTEST/api/v1/contests/$CID/announcements" "$CAND_TOKEN" ""
check "考生拉取公告（即送达）→ 200" 200 "$CODE"
check "  deliveredAt 已记录"      "true" "$([ -n "$(jget "$BODY" "data.0.deliveredAt")" ] && echo true || echo false)"
api POST "$CONTEST/api/v1/contests/$CID/announcements/$AID/confirm" "$CAND_TOKEN" ""
check "考生确认公告 → 200"        200 "$CODE"
api GET "$CONTEST/api/v1/contests/$CID/announcements" "$CAND_TOKEN" ""
check "重复拉取（确认幂等）→ 200" 200 "$CODE"
check "  confirmedAt 已记录"      "true" "$([ -n "$(jget "$BODY" "data.0.confirmedAt")" ] && echo true || echo false)"

section "6. 进行中的考试（时间窗口驱动 RUNNING + 会话）"
START_AT=$(date -u -d '-10 minutes' +%Y-%m-%dT%H:%M:%SZ)
END_AT=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)
api POST "$CONTEST/api/v1/contests" "$ADMIN_TOKEN" "{\"name\":\"进行中考试 $TS\",\"description\":\"会话与提交测试\",\"startAt\":\"$START_AT\",\"endAt\":\"$END_AT\"}"
CID2=$(jget "$BODY" "data.id")
api POST "$CONTEST/api/v1/contests/$CID2/review" "$ADMIN_TOKEN" "" >/dev/null
api POST "$CONTEST/api/v1/contests/$CID2/publish" "$ADMIN_TOKEN" "" >/dev/null
check "创建窗口内考试并发布 → id 非空" "true" "$([ -n "$CID2" ] && echo true || echo false)"

STATUS=""
for i in $(seq 1 20); do
  api GET "$CONTEST/api/v1/contests/$CID2" "$ADMIN_TOKEN" ""
  STATUS=$(jget "$BODY" "data.status")
  [ "$STATUS" = "RUNNING" ] && break
  sleep 3
done
check "调度器推进 PUBLISHED→RUNNING" RUNNING "$STATUS"

api POST "$CONTEST/api/v1/contests/$CID2/students" "$ADMIN_TOKEN" "{\"studentNo\":\"$SNO\",\"username\":\"student_$SNO\",\"displayName\":\"测试学生\",\"initialPassword\":\"whatever-999\"}"
check "同一学生加入第二场（账号复用）→ 200" 200 "$CODE"
check "  复用同一 studentId"      "$SID" "$(jget "$BODY" "data.studentId")"

api POST "$CONTEST/api/v1/contests/$CID2/session" "$CAND_TOKEN" ""
check "考生进入会话 → 200"        200 "$CODE"
check "  会话 ACTIVE"             ACTIVE "$(jget "$BODY" "data.status")"
check "  剩余秒数 > 0"            "true" "$([ "$(jget "$BODY" "data.remainingSeconds")" -gt 0 ] && echo true || echo false)"
api POST "$CONTEST/api/v1/contests/$CID2/session/heartbeat" "$CAND_TOKEN" ""
check "会话心跳 → 200"            200 "$CODE"
api POST "$CONTEST/api/v1/contests/$CID2/session" "$ADMIN_TOKEN" ""
check "管理员进会话（非考生）→ 403" 403 "$CODE"
api POST "$CONTEST/api/v1/contests/$CID/session" "$CAND_TOKEN" ""
check "未到开始时间进会话 → 409"  409 "$CODE"

# BE-04 起：题目必须先加入考试并锁定版本，提交才会被接受（否则 404 SUBMISSION_PROBLEM_NOT_IN_CONTEST）
api POST "$CONTEST/api/v1/contests/$CID2/problems" "$ADMIN_TOKEN" "{\"problemId\":\"$PROB_ID\",\"problemVersionId\":\"$VER1\"}"
check "题目加入考试并锁定版本 → 200"  200 "$CODE"
section "7. submission-service (8084)"
SUB_BODY="{\"contestId\":\"$CID2\",\"problemId\":\"$PROB_ID\",\"language\":\"cpp\",\"sourceCode\":\"#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;return 0;}\"}"
api POST "$SUBMISSION/api/v1/submissions" "$CAND_TOKEN" "$SUB_BODY" "idem-$TS-1"
check "提交代码 → 200"            200 "$CODE"
SUB_ID=$(jget "$BODY" "data.submissionId")
require_id "$SUB_ID" "提交失败，SUB_ID 为空"
check "  初始状态 QUEUED"         QUEUED "$(jget "$BODY" "data.status")"

api POST "$SUBMISSION/api/v1/submissions" "$CAND_TOKEN" "$SUB_BODY" "idem-$TS-1"
check "同幂等键 + 同内容 → 200"   200 "$CODE"
check "  返回首次结果（同 id）"   "$SUB_ID" "$(jget "$BODY" "data.submissionId")"

api POST "$SUBMISSION/api/v1/submissions" "$CAND_TOKEN" "{\"contestId\":\"$CID2\",\"problemId\":\"$PROB_ID\",\"language\":\"cpp\",\"sourceCode\":\"int main(){} // 不同内容\"}" "idem-$TS-1"
check "同幂等键 + 不同内容 → 409" 409 "$CODE"
check "  SUBMISSION_DUPLICATE"    SUBMISSION_DUPLICATE "$(jget "$BODY" "error.code")"

api POST "$SUBMISSION/api/v1/submissions" "$CAND_TOKEN" "{\"contestId\":\"$CID2\",\"problemId\":\"$PROB_ID\",\"language\":\"ruby\",\"sourceCode\":\"puts 1\"}"
check "语言不在白名单 → 400"      400 "$CODE"
check "  SUBMISSION_LANGUAGE_NOT_ALLOWED" SUBMISSION_LANGUAGE_NOT_ALLOWED "$(jget "$BODY" "error.code")"

api POST "$SUBMISSION/api/v1/submissions" "$ADMIN_TOKEN" "$SUB_BODY"
check "管理员提交（非考生）→ 403" 403 "$CODE"
api POST "$SUBMISSION/api/v1/submissions" "" "$SUB_BODY"
check "无令牌提交 → 401"          401 "$CODE"

api GET "$SUBMISSION/api/v1/submissions/$SUB_ID" "$CAND_TOKEN" ""
check "考生查自己的提交 → 200"    200 "$CODE"
api GET "$SUBMISSION/api/v1/submissions/$SUB_ID" "$ADMIN_TOKEN" ""
check "老师/管理员查任意提交 → 200" 200 "$CODE"
api GET "$SUBMISSION/api/v1/submissions/00000000-0000-0000-0000-0000000000de" "$ADMIN_TOKEN" ""
check "不存在的提交 → 404"        404 "$CODE"

printf '  ....  等待评测链路（Outbox→RocketMQ→judge→回调）完成：'
VERDICT=""
for i in $(seq 1 40); do
  api GET "$SUBMISSION/api/v1/submissions/$SUB_ID" "$ADMIN_TOKEN" ""
  VERDICT=$(jget "$BODY" "data.status")
  case "$VERDICT" in QUEUED|JUDGING|RUNNING|"") printf '.'; sleep 3;; *) break;; esac
done
printf ' 终态=%s\n' "$VERDICT"
check "评测链路产出终态 verdict"  "$EXPECTED_VERDICT" "$VERDICT"
if [ "$EXPECTED_VERDICT" = "ACCEPTED" ]; then
  check "  ACCEPTED 记 100 分"    100 "$(jget "$BODY" "data.score")"
fi

section "8. anti-cheat-service (8086)"
api POST "$ANTICHEAT/api/v1/monitor/events" "$CAND_TOKEN" "{\"contestId\":\"$CID2\",\"eventType\":\"DEBUGGER_DETECTED\",\"problemId\":\"$PROB_ID\",\"detail\":\"检测到调试器\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
check "考生上报风险事件 → 200"    200 "$CODE"
EVENT_ID=$(jget "$BODY" "data.eventId")
require_id "$EVENT_ID" "上报风险事件失败，EVENT_ID 为空"
check "  等级由服务端规则表计算 HIGH" HIGH "$(jget "$BODY" "data.severity")"

api POST "$ANTICHEAT/api/v1/monitor/events" "$ADMIN_TOKEN" "{\"contestId\":\"$CID2\",\"eventType\":\"FOCUS_LOST\"}"
check "非考生上报 → 403"          403 "$CODE"
api POST "$ANTICHEAT/api/v1/monitor/events" "" "{\"contestId\":\"$CID2\",\"eventType\":\"FOCUS_LOST\"}"
check "无令牌上报 → 401"          401 "$CODE"
api POST "$ANTICHEAT/api/v1/monitor/events" "$CAND_TOKEN" "{\"contestId\":\"$CID2\",\"eventType\":\"\"}"
check "未知事件类型 → 400"          400 "$CODE"

api GET "$ANTICHEAT/api/v1/monitor/events?contestId=$CID2&page=1&pageSize=20" "$ADMIN_TOKEN" ""
check "监考查询事件 → 200"        200 "$CODE"
check "  含 items"                "true" "$([ "$(jlen "$BODY" "data.items")" -ge 1 ] && echo true || echo false)"
api GET "$ANTICHEAT/api/v1/monitor/events" "$CAND_TOKEN" ""
check "考生查询事件（非监考角色）→ 403" 403 "$CODE"
api GET "$ANTICHEAT/api/v1/monitor/events" "" ""
check "无令牌查询 → 401"          401 "$CODE"

api POST "$ANTICHEAT/api/v1/monitor/events/$EVENT_ID/review" "$ADMIN_TOKEN" '{"action":"ACKNOWLEDGE","note":"已关注"}'
check "审核 ACKNOWLEDGE → 200"    200 "$CODE"
check "  状态 ACKNOWLEDGED"       ACKNOWLEDGED "$(jget "$BODY" "data.reviewStatus")"
api POST "$ANTICHEAT/api/v1/monitor/events/$EVENT_ID/review" "$ADMIN_TOKEN" '{"action":"CONFIRM","note":"确认违规"}'
check "审核 CONFIRM → 200"        200 "$CODE"
check "  状态 CONFIRMED"          CONFIRMED "$(jget "$BODY" "data.reviewStatus")"
api POST "$ANTICHEAT/api/v1/monitor/events/$EVENT_ID/review" "$ADMIN_TOKEN" '{"action":"DISMISS"}'
check "终态再审核 → 409"          409 "$CODE"
check "  MONITOR_INVALID_TRANSITION" MONITOR_INVALID_TRANSITION "$(jget "$BODY" "error.code")"
api POST "$ANTICHEAT/api/v1/monitor/events/00000000-0000-0000-0000-0000000000de/review" "$ADMIN_TOKEN" '{"action":"ACKNOWLEDGE"}'
check "不存在的事件审核 → 404"    404 "$CODE"
check "  MONITOR_EVENT_NOT_FOUND" MONITOR_EVENT_NOT_FOUND "$(jget "$BODY" "error.code")"

section "9. audit-service (8087)"
api POST "$AUDIT/api/v1/internal/audit/records" "$ADMIN_TOKEN" "{\"action\":\"CONTEST_CREATED\",\"targetType\":\"CONTEST\",\"targetId\":\"$CID2\",\"contestId\":\"$CID2\",\"detail\":\"全量测试写入\"}"
check "服务间写审计记录 → 200"    200 "$CODE"
check "  actorRole=ADMIN"         ADMIN "$(jget "$BODY" "data.actorRole")"
api GET "$AUDIT/api/v1/audit/records?contestId=$CID2&page=1&pageSize=20" "$ADMIN_TOKEN" ""
check "管理端查询账本 → 200"      200 "$CODE"
check "  含 items"                "true" "$([ "$(jlen "$BODY" "data.items")" -ge 1 ] && echo true || echo false)"
api GET "$AUDIT/api/v1/audit/records" "$CAND_TOKEN" ""
check "考生查询账本 → 403"        403 "$CODE"
api GET "$AUDIT/api/v1/audit/records" "" ""
check "无令牌查询 → 401"          401 "$CODE"

section "10. gateway-service (8080) 与边缘"
api GET "$GATEWAY/api/v1/auth/jwks" "" ""
check "网关 → auth /jwks → 200"   200 "$CODE"
api POST "$GATEWAY/api/v1/auth/login" "" '{"username":"admin","password":"admin123"}'
check "网关 → auth /login → 200"  200 "$CODE"
api GET "$GATEWAY/api/v1/contests/$CID" "$ADMIN_TOKEN" ""
check "网关 → contest 查询 → 200" 200 "$CODE"
api GET "$GATEWAY/api/v1/problems/$PROB_ID" "$ADMIN_TOKEN" ""
check "网关 → problem 查询 → 200" 200 "$CODE"
api GET "$GATEWAY/api/v1/submissions/$SUB_ID" "$ADMIN_TOKEN" ""
check "网关 → submission 查询 → 200" 200 "$CODE"
api GET "$GATEWAY/api/v1/monitor/events?contestId=$CID2" "$ADMIN_TOKEN" ""
check "网关 → anti-cheat 查询 → 200" 200 "$CODE"
api GET "$GATEWAY/api/v1/audit/records" "$ADMIN_TOKEN" ""
check "网关 → audit 查询 → 200"   200 "$CODE"
api GET "$GATEWAY/api/v1/internal/students?studentNo=x" "$ADMIN_TOKEN" ""
check "网关不暴露 internal → 404" 404 "$CODE"
api GET "$GATEWAY/api/v1/monitor/events" "" ""
check "网关未认证 → 401"          401 "$CODE"
check "边缘代理 → 网关 → auth"    200 "$(curl -s -o /dev/null -m 15 -w '%{http_code}' $EDGE/api/v1/auth/jwks)"

section "11. 考试归档（收尾）"
api POST "$CONTEST/api/v1/contests/$CID2/archive" "$ADMIN_TOKEN" ""
check "RUNNING 直接归档 → 409"    409 "$CODE"
api GET "$CONTEST/api/v1/contests/$CID2" "$ADMIN_TOKEN" ""
api POST "$CONTEST/api/v1/contests/$CID/archive" "$ADMIN_TOKEN" ""
check "PUBLISHED 直接归档 → 409"  409 "$CODE"

printf '\n########################################\n'
printf '#  通过 %s   失败 %s\n' "$PASS" "$FAIL"
printf '########################################\n'
if [ "$FAIL" != "0" ]; then printf '失败明细：%s\n' "$FAILURES"; fi
