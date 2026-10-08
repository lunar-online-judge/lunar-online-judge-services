package io.lunar.audit.domain;

import io.lunar.audit.infra.AuditLogEntity;
import io.lunar.audit.infra.AuditLogMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditService service = new AuditService(mapper);

    @BeforeAll
    static void initMpMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AuditLogEntity.class);
    }

    @Test
    void recordPersistsAllFields() {
        var view = service.record("RISK_EVENT_REVIEWED", "teacher_1", "TEACHER",
                "RISK_EVENT", "evt_1", "c1", "OPEN->CONFIRMED", "trace-1");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        AuditLogEntity e = captor.getValue();
        assertThat(e.getAction()).isEqualTo("RISK_EVENT_REVIEWED");
        assertThat(e.getActorId()).isEqualTo("teacher_1");
        assertThat(e.getActorRole()).isEqualTo("TEACHER");
        assertThat(e.getTargetId()).isEqualTo("evt_1");
        assertThat(e.getContestId()).isEqualTo("c1");
        assertThat(e.getDetail()).isEqualTo("OPEN->CONFIRMED");
        assertThat(e.getTraceId()).isEqualTo("trace-1");
        assertThat(view.id()).isNotBlank();
    }

    @Test
    void recordWithoutActorIsSystemAction() {
        service.record("EXAM_STARTED", null, null, "CONTEST", "c1", "c1", null, null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getActorRole()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().getActorId()).isNull();
    }

    @Test
    void queryAppliesFiltersAndPagination() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AuditLogEntity>(1, 20, 0);
        page.setRecords(List.of());
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var result = service.query("STUDENT_CREATED", "teacher_1", "c1", 1, 20);

        assertThat(result.total()).isZero();
        verify(mapper).selectPage(any(), any());
    }
}
