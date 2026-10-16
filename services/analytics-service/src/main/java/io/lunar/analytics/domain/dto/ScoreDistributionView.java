package io.lunar.analytics.domain.dto;

import java.util.List;

/**
 * 分数分布视图。
 *
 * {@code bucketSize} 回显请求里实际生效的档宽：调用方可能传了越界值被夹取/拒绝，
 * 不告诉它实际档宽的话，前端画出来的坐标轴会和数据对不上。
 */
public record ScoreDistributionView(int bucketSize,
                                    int studentCount,
                                    Double averageScore,
                                    Integer maxScore,
                                    List<ScoreBucket> buckets) {
}
