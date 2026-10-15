package io.lunar.audit.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 审计写入请求体（服务间）：actorId / actorRole 由服务端从令牌推导，不由请求体传入。 */
public record RecordRequest(@NotBlank String action, String targetType, String targetId,
                            String contestId, String detail) {}
