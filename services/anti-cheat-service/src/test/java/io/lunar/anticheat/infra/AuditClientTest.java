package io.lunar.anticheat.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 适配层职责：转发 Bearer 令牌、把 traceId 作为 X-Request-Id 透传、空字段填成空串。 */
class AuditClientTest {

    private final AuditServiceApi auditApi = mock(AuditServiceApi.class);
    private final AuditClient client = new AuditClient(auditApi);

    @Test
    void recordForwardsTraceIdAndNormalizesBlankFields() {
        client.record("RISK_EVENT_REVIEWED", null, "e1", "c1", "确认作弊", "trace-9", "tok");

        verify(auditApi).record("Bearer tok", "trace-9", Map.of(
                "action", "RISK_EVENT_REVIEWED",
                "targetType", "",
                "targetId", "e1",
                "contestId", "c1",
                "detail", "确认作弊"));
    }
}
