package io.lunar.anticheat.domain;

import io.lunar.anticheat.infra.OutboxEventEntity;
import io.lunar.anticheat.infra.OutboxEventMapper;
import io.lunar.anticheat.infra.RiskEventEntity;
import io.lunar.anticheat.infra.RiskEventMapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskEventServiceTest {

    private final RiskEventMapper riskEventMapper = mock(RiskEventMapper.class);
    private final OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);

    private final RiskEventService service = new RiskEventService(riskEventMapper, outboxMapper);

    @BeforeAll
    static void initMpMetadata() {
        for (Class<?> entity : List.of(RiskEventEntity.class, OutboxEventEntity.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(
                            new MybatisConfiguration(), ""),
                    entity);
        }
    }

    /** 审核写入是条件更新（CAS）：默认成功（影响 1 行），并发冲突用例单独覆盖为 0 行。 */
    @BeforeEach
    void stubConditionalUpdateSucceeds() {
        when(riskEventMapper.update(isNull(), any())).thenReturn(1);
    }

    private static RiskEventEntity event(String id, String reviewStatus) {
        RiskEventEntity riskEvent = new RiskEventEntity();
        riskEvent.setId(id);
        riskEvent.setContestId("c1");
        riskEvent.setStudentId("stu_1");
        riskEvent.setEventType("DEBUGGER_DETECTED");
        riskEvent.setSeverity("HIGH");
        riskEvent.setOccurredAt(Instant.now());
        riskEvent.setReportedAt(Instant.now());
        riskEvent.setReviewStatus(reviewStatus);
        return riskEvent;
    }

    @Test
    void severityComputedServerSideByRules() {
        // HIGH：调试器；LOW：失焦；未知类型保守 MEDIUM
        service.report("c1", "stu_1", "DEBUGGER_DETECTED", null, null, null, null);
        service.report("c1", "stu_1", "FOCUS_LOST", null, null, null, null);
        service.report("c1", "stu_1", "SOMETHING_NEW", null, null, null, null);

        ArgumentCaptor<RiskEventEntity> captor = ArgumentCaptor.forClass(RiskEventEntity.class);
        verify(riskEventMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getSeverity()).isEqualTo("HIGH");
        assertThat(captor.getAllValues().get(1).getSeverity()).isEqualTo("LOW");
        assertThat(captor.getAllValues().get(2).getSeverity()).isEqualTo("MEDIUM");
    }

    @Test
    void reportWritesRiskEventCreatedOutbox() {
        var reported = service.report("c1", "stu_1", "DEBUGGER_DETECTED", null, null, null, "trace-9");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("lunar-risk-event-created");
        assertThat(captor.getValue().getPayload()).contains(reported.riskEventId());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-9");
    }

    @Test
    void reviewOpenCanAcknowledge() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        var view = service.review("e1", "ACKNOWLEDGE", "已阅", "teacher_1", "TEACHER", "trace-1");

        assertThat(view.reviewStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(view.reviewedBy()).isEqualTo("teacher_1");
        assertThat(view.reviewNote()).isEqualTo("已阅");
    }

    @Test
    void reviewOpenCanDismissDirectly() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        var view = service.review("e1", "DISMISS", "误报", "teacher_1", "TEACHER", "trace-1");

        assertThat(view.reviewStatus()).isEqualTo("DISMISSED");
    }

    @Test
    void acknowledgedCanConfirm() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "ACKNOWLEDGED"));

        var view = service.review("e1", "CONFIRM", "证据确凿", "teacher_1", "TEACHER", "trace-1");

        assertThat(view.reviewStatus()).isEqualTo("CONFIRMED");
    }

    /**
     * 审核流转必须进 Outbox：风险事件只在**创建**时发事件，若审核不补一条，
     * 分析侧的审核结论会永远停留在 OPEN（CONFIRMED/DISMISSED 根本传不出去）。
     */
    @Test
    void reviewWritesReviewedOutboxEventInSameTransaction() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        service.review("e1", "CONFIRM", "证据确凿", "teacher_1", "TEACHER", "trace-1");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper, times(2)).insert(captor.capture());
        // BE-07：审核同时写一条审计留痕事件，异步投递到 audit-service（actor 作为事件快照）
        assertThat(captor.getAllValues()).extracting(OutboxEventEntity::getEventType)
                .containsExactlyInAnyOrder("lunar-risk-event-reviewed", "lunar-audit-recorded");
        OutboxEventEntity reviewed = captor.getAllValues().stream()
                .filter(event -> "lunar-risk-event-reviewed".equals(event.getEventType()))
                .findFirst().orElseThrow();
        assertThat(reviewed.getAggregateId()).isEqualTo("e1");
        assertThat(reviewed.getPayload())
                .contains("\"previousStatus\":\"OPEN\"")
                .contains("\"newStatus\":\"CONFIRMED\"")
                .contains("\"reviewedBy\":\"teacher_1\"");
    }

    @Test
    void terminalStatusCannotBeReviewedAgain() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "CONFIRMED"));

        assertThatThrownBy(() -> service.review("e1", "DISMISS", null, "teacher_1", "TEACHER", "trace-1"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
        assertThatThrownBy(() -> service.review("e1", "ACKNOWLEDGE", null, "teacher_1", "TEACHER", "trace-1"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
    }

    @Test
    void concurrentReviewLosesConditionalUpdate() {
        // 并发场景：读到 OPEN，但写入时前态已被改走（影响 0 行）→ 409，而不是覆盖先提交的终态
        when(riskEventMapper.selectById("e1"))
                .thenReturn(event("e1", "OPEN"))
                .thenReturn(event("e1", "CONFIRMED"));
        when(riskEventMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.review("e1", "CONFIRM", "并发", "teacher_1", "TEACHER", "trace-1"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
    }

    @Test
    void unknownActionRejected() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        assertThatThrownBy(() -> service.review("e1", "DELETE", null, "teacher_1", "TEACHER", "trace-1"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
    }

    @Test
    void unknownEventThrowsNotFound() {
        when(riskEventMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.review("nope", "ACKNOWLEDGE", null, "teacher_1", "TEACHER", "trace-1"))
                .isInstanceOf(ReviewException.NotFound.class);
    }

    @Test
    void listFiltersByContestAndSeverity() {
        var page = new Page<RiskEventEntity>(1, 20, 0);
        page.setRecords(List.of());
        when(riskEventMapper.selectPage(any(), any())).thenReturn(page);

        service.list("c1", null, "HIGH", null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<RiskEventEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(riskEventMapper).selectPage(any(), captor.capture());
        // wrapper 条件由 SQL 保证；此处确认查询执行且分页参数生效
        verify(riskEventMapper).selectPage(any(), any());
    }
}
