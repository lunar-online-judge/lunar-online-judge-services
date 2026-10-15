package io.lunar.contest.domain.dto;

import java.time.Instant;

/** 考试会话视图：remainingSeconds 按竞赛 endAt 计算，断线重连不补偿。 */
public record SessionView(String sessionId, String status, Instant startedAt, long remainingSeconds) {}
