package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 发布平台公告请求：标题 + 正文 + 是否重要。 */
public record CreatePlatformAnnouncementRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,

        @NotBlank(message = "公告内容不能为空")
        @Size(max = 4000, message = "公告内容不能超过 4000 字")
        String content,

        boolean important) {
}
