package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 重算结果：让调用方拿到"这次到底算了多少行、什么时候算的"，而不是只有一个 200。
 */
public record RebuildResult(String contestId, int studentCount, Instant computedAt) {
}
