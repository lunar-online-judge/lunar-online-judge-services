package io.lunar.contest.domain.dto;

import java.time.Instant;

/** 平台公告视图。 */
public record PlatformAnnouncementView(String announcementId, String title, String content,
                                       boolean important, String createdBy, Instant createdAt) {
}
