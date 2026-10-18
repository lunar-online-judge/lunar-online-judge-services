package io.lunar.contest.domain.dto;

import java.util.List;

/** 平台公告分页响应。 */
public record PlatformAnnouncementPage(List<PlatformAnnouncementView> items, long total,
                                       long page, long pageSize) {
}
