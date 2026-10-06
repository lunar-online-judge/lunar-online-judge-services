package io.lunar.contest.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContestStatusTest {

    @Test
    void lifecycleTransitionsAreLinear() {
        assertThat(ContestStatus.DRAFT.canTransitionTo(ContestStatus.REVIEWING)).isTrue();
        assertThat(ContestStatus.REVIEWING.canTransitionTo(ContestStatus.PUBLISHED)).isTrue();
        assertThat(ContestStatus.PUBLISHED.canTransitionTo(ContestStatus.RUNNING)).isTrue();
        assertThat(ContestStatus.RUNNING.canTransitionTo(ContestStatus.FINISHED)).isTrue();
        assertThat(ContestStatus.FINISHED.canTransitionTo(ContestStatus.ARCHIVED)).isTrue();
    }

    @Test
    void skippingAndBackwardTransitionsAreIllegal() {
        assertThat(ContestStatus.DRAFT.canTransitionTo(ContestStatus.PUBLISHED)).isFalse();
        assertThat(ContestStatus.DRAFT.canTransitionTo(ContestStatus.RUNNING)).isFalse();
        assertThat(ContestStatus.REVIEWING.canTransitionTo(ContestStatus.DRAFT)).isFalse();
        assertThat(ContestStatus.PUBLISHED.canTransitionTo(ContestStatus.REVIEWING)).isFalse();
        assertThat(ContestStatus.ARCHIVED.canTransitionTo(ContestStatus.DRAFT)).isFalse();
        // 原地流转不允许
        assertThat(ContestStatus.RUNNING.canTransitionTo(ContestStatus.RUNNING)).isFalse();
    }
}
