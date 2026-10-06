package io.lunar.contest.domain;

import java.util.EnumSet;
import java.util.Map;

/**
 * 考试生命周期（docs/exam-process.md §1）：
 * DRAFT -> REVIEWING -> PUBLISHED -> RUNNING -> FINISHED -> ARCHIVED，单向不可跳步。
 * RUNNING/FINISHED/ARCHIVED 的触发（时间窗口驱动/手动结束）随考试时间管理特性落地，本枚举只约束合法性。
 */
public enum ContestStatus {

    DRAFT, REVIEWING, PUBLISHED, RUNNING, FINISHED, ARCHIVED;

    private static final Map<ContestStatus, EnumSet<ContestStatus>> NEXT = Map.of(
            DRAFT, EnumSet.of(REVIEWING),
            REVIEWING, EnumSet.of(PUBLISHED),
            PUBLISHED, EnumSet.of(RUNNING),
            RUNNING, EnumSet.of(FINISHED),
            FINISHED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(ContestStatus.class));

    public boolean canTransitionTo(ContestStatus target) {
        return NEXT.get(this).contains(target);
    }
}
