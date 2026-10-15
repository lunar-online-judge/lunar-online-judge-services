package io.lunar.anticheat.domain.dto;

import java.time.Instant;

/** 上报回执：severity 由服务端规则表计算，不由客户端传入。 */
public record ReportedEvent(String eventId, String riskEventId, String eventType, String severity,
                            Instant occurredAt) {}
