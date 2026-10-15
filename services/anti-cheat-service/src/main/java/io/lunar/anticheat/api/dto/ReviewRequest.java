package io.lunar.anticheat.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 风险事件审核请求体：action = ACKNOWLEDGE / CONFIRM / DISMISS。 */
public record ReviewRequest(@NotBlank String action, String note) {}
