package io.lunar.analytics.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 显式重算的请求体。
 *
 * 只接受 contestId：重算的粒度就是"一场考试"——全库重算会把所有正在进行的考试一起锁住，
 * 而那个操作没有任何合法用途（历史成绩不会因为别的考试的口径变化而改变）。
 */
public record RebuildRequest(@NotBlank String contestId) {
}
