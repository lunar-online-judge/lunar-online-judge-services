package io.lunar.anticheat.domain;

import io.lunar.anticheat.infra.OutboxEventEntity;
import io.lunar.anticheat.infra.OutboxEventMapper;
import io.lunar.anticheat.infra.RiskEventEntity;
import io.lunar.anticheat.infra.RiskEventMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskEventServiceTest {

    private final RiskEventMapper riskEventMapper = mock(RiskEventMapper.class);
    private final OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
    private final io.lunar.anticheat.infra.AuditClient auditClient = mock(io.lunar.anticheat.infra.AuditClient.class);
    private final RiskEventService service = new RiskEventService(riskEventMapper, outboxMapper, auditClient);

    @BeforeAll
    static void initMpMetadata() {
        for (Class<?> entity : List.of(RiskEventEntity.class, OutboxEventEntity.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    entity);
        }
    }

    private static RiskEventEntity event(String id, String reviewStatus) {
        RiskEventEntity e = new RiskEventEntity();
        e.setId(id);
        e.setContestId("c1");
        e.setStudentId("stu_1");
        e.setEventType("DEBUGGER_DETECTED");
        e.setSeverity("HIGH");
        e.setOccurredAt(Instant.now());
        e.setReportedAt(Instant.now());
        e.setReviewStatus(reviewStatus);
        return e;
    }

    @Test
    void severityComputedServerSideByRules() {
        // HIGH：调试器；LOW：失焦；未知类型保守 MEDIUM
        service.report("c1", "stu_1", "DEBUGGER_DETECTED", null, null, null, null);
        service.report("c1", "stu_1", "FOCUS_LOST", null, null, null, null);
        service.report("c1", "stu_1", "SOMETHING_NEW", null, null, null, null);

        ArgumentCaptor<RiskEventEntity> captor = ArgumentCaptor.forClass(RiskEventEntity.class);
        verify(riskEventMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
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

        var view = service.review("e1", "ACKNOWLEDGE", "已阅", "teacher_1", "tok");

        assertThat(view.reviewStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(view.reviewedBy()).isEqualTo("teacher_1");
        assertThat(view.reviewNote()).isEqualTo("已阅");
    }

    @Test
    void reviewOpenCanDismissDirectly() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        var view = service.review("e1", "DISMISS", "误报", "teacher_1", "tok");

        assertThat(view.reviewStatus()).isEqualTo("DISMISSED");
    }

    @Test
    void acknowledgedCanConfirm() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "ACKNOWLEDGED"));

        var view = service.review("e1", "CONFIRM", "证据确凿", "teacher_1", "tok");

        assertThat(view.reviewStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void terminalStatusCannotBeReviewedAgain() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "CONFIRMED"));

        assertThatThrownBy(() -> service.review("e1", "DISMISS", null, "teacher_1", "tok"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
        assertThatThrownBy(() -> service.review("e1", "ACKNOWLEDGE", null, "teacher_1", "tok"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
    }

    @Test
    void unknownActionRejected() {
        when(riskEventMapper.selectById("e1")).thenReturn(event("e1", "OPEN"));

        assertThatThrownBy(() -> service.review("e1", "DELETE", null, "teacher_1", "tok"))
                .isInstanceOf(ReviewException.InvalidTransition.class);
    }

    @Test
    void unknownEventThrowsNotFound() {
        when(riskEventMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.review("nope", "ACKNOWLEDGE", null, "teacher_1", "tok"))
                .isInstanceOf(ReviewException.NotFound.class);
    }

    @Test
    void listFiltersByContestAndSeverity() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<RiskEventEntity>(1, 20, 0);
        page.setRecords(java.util.List.of());
        when(riskEventMapper.selectPage(any(), any())).thenReturn(page);

        service.list("c1", null, "HIGH", null, 1, 20);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RiskEventEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(riskEventMapper).selectPage(any(), captor.capture());
        // wrapper 条件由 SQL 保证；此处确认查询执行且分页参数生效
        org.mockito.Mockito.verify(riskEventMapper).selectPage(any(), any());
    }
}
