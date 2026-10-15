package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 发布公告请求体：important / requireConfirmation 为可空布尔，缺省按 false 处理。 */
public record PublishAnnouncementRequest(@NotBlank String content,
                                         Boolean important,
                                         Boolean requireConfirmation) {}
