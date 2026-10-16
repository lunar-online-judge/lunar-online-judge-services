package io.lunar.common.audit;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenProvider;
import io.lunar.common.identity.ServiceTokenResponse;
import io.lunar.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 审计留痕封装的行为契约：转发 Bearer 令牌与 traceId、可选字段填空串、
 * 以及"账本不可用只告警、不把异常抛给业务"（这是各服务敢直接调它的前提）。
 */
class AuditRecorderTest {

    @Test
    void recordForwardsBearerTokenTraceIdAndFields() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        List<AuditRecordCommand> commands = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> {
            authorization.set(auth);
            requestId.set(trace);
            commands.add(command);
        });

        recorder.record("RISK_EVENT_REVIEWED", "RISK_EVENT", "e1", "c1", "确认作弊", "trace-9", "tok");

        assertEquals("Bearer tok", authorization.get());
        assertEquals("trace-9", requestId.get());
        assertEquals(List.of(new AuditRecordCommand("RISK_EVENT_REVIEWED", "RISK_EVENT", "e1", "c1", "确认作弊")),
                commands);
    }

    @Test
    void recordKeepsAbsentOptionalFieldsNull() {
        // contestId 在账本里是 uuid 列：缺省必须传 null，传空串会被 PostgreSQL 拒绝（实测踩过）
        List<AuditRecordCommand> commands = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> commands.add(command));

        recorder.record("STUDENT_CREATED", null, "stu_1", null, null, null, "Bearer tok");

        assertEquals(List.of(new AuditRecordCommand("STUDENT_CREATED", null, "stu_1", null, null)), commands);
    }

    @Test
    void recordDoesNotRepeatBearerPrefix() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> authorization.set(auth));

        recorder.record("STUDENT_REMOVED", "ENROLLMENT", "stu_1", "c1", "移出名单", null, "Bearer tok");

        assertEquals("Bearer tok", authorization.get());
    }

    @Test
    void recordSwallowsFailuresSoBusinessFlowContinues() {
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> {
            throw new IllegalStateException("connection refused");
        });

        // 不抛异常即通过：审计不可用不能阻断业务动作
        recorder.record("STUDENT_ENROLLED", "ENROLLMENT", "stu_1", "c1", "加入名单", null, "tok");
    }

    @Test
    void recordFallsBackToCurrentRequestTraceId() {
        AtomicReference<String> requestId = new AtomicReference<>();
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> requestId.set(trace));
        MDC.put(RequestIdFilter.MDC_KEY, "trace-from-request");
        try {
            recorder.record("STUDENT_ENROLLED", "ENROLLMENT", "stu_1", "c1", "加入名单", null, "tok");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        // 调用方没传 traceId 时接上当前请求的 X-Request-Id，账本链路不断
        assertEquals("trace-from-request", requestId.get());
    }

    @Test
    void recordWithoutTokenAndWithoutServiceIdentitySkipsUpstreamCall() {
        List<AuditRecordCommand> commands = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder((auth, trace, command) -> commands.add(command));

        recorder.record("STUDENT_ENROLLED", "ENROLLMENT", "stu_1", "c1", "加入名单", null, null);

        // 账本接口要求已认证：没有令牌就不发注定 401 的请求
        assertEquals(List.of(), commands);
    }

    @Test
    void recordFallsBackToServiceTokenWhenNoOperatorToken() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AuditRecorder recorder = new AuditRecorder(
                (auth, trace, command) -> authorization.set(auth),
                new ServiceTokenProvider(
                        request -> ApiResponse.success("req", new ServiceTokenResponse(
                                "service-token", "Bearer", Instant.now().plusSeconds(600), "SERVICE")),
                        "contest-service", "secret"));

        recorder.record("EXAM_STARTED", "CONTEST", null, null, "按时间窗口自动开始 1 场考试", null, null);

        assertEquals("Bearer service-token", authorization.get());
    }

    @Test
    void recordSkipsWhenServiceTokenUnavailable() {
        List<AuditRecordCommand> commands = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder(
                (auth, trace, command) -> commands.add(command),
                new ServiceTokenProvider(request -> {
                    throw new IllegalStateException("auth-service 不可用");
                }, "contest-service", "secret"));

        // 服务令牌取不到也不抛异常：审计尽力而为，业务主流程不受影响
        recorder.record("EXAM_FINISHED", "CONTEST", null, null, "按时间窗口自动结束 1 场考试", null, null);

        assertEquals(List.of(), commands);
    }
}
