package io.lunar.contest.domain.dto;

import java.time.Instant;

/** 公告视图：老师视角不含送达/确认时间，考生视角附带自己的 deliveredAt 与 confirmedAt。 */
public record AnnouncementView(String announcementId, String content, boolean important,
                               boolean requireConfirmation, String createdBy, Instant createdAt,
                               Instant deliveredAt, Instant confirmedAt) {}
