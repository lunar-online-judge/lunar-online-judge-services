package io.lunar.analytics.domain.dto;

import java.time.Instant;

/** 提交时间线的一条：只带判定结论，不带源码（源码属于 submission-service 的权限范围）。 */
public record SubmissionTimelineEntry(String submissionId,
                                      String problemId,
                                      String verdict,
                                      Integer score,
                                      Instant judgedAt) {
}
