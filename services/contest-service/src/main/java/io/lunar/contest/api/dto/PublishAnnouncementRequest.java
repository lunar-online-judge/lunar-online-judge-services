package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 发布公告请求体：important / requireConfirmation 为可空布尔，缺省按 false 处理；正文有服务端长度上限（BE-10）。 */
public record PublishAnnouncementRequest(@NotBlank @Size(max = 4000) String content,
                                         Boolean important,
                                         Boolean requireConfirmation) {}
