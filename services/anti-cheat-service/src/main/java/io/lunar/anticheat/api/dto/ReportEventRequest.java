package io.lunar.anticheat.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** 风险事件上报请求体：severity 由服务端规则表计算，不由客户端传入。 */
public record ReportEventRequest(@NotBlank String contestId, @NotBlank String eventType,
                                 String problemId, String detail, Instant occurredAt) {}
