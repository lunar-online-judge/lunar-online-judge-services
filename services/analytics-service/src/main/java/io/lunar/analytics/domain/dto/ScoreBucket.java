package io.lunar.analytics.domain.dto;

/** 分数直方图的一档：区间闭区间，{@code bucketFrom..bucketTo}。 */
public record ScoreBucket(int bucketFrom, int bucketTo, int studentCount) {
}
