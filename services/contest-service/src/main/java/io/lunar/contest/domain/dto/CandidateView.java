package io.lunar.contest.domain.dto;

import java.time.Instant;

/** 名单视图：取报名时的快照列（权威值在 auth_schema.users），供名单展示与审计。 */
public record CandidateView(String studentId, String studentNo, String username, String displayName,
                            String enrollmentStatus, String addedByTeacherId, Instant enrolledAt) {}
