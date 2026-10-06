package io.lunar.contest.domain;

import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestServiceTest {

    private final ContestMapper mapper = mock(ContestMapper.class);
    private final ContestService service = new ContestService(mapper);

    static {
        // LambdaUpdateWrapper 依赖 MP 的实体元数据缓存；纯单测需手动初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                io.lunar.contest.infra.ContestEntity.class);
    }

    private static ContestEntity entity(String id, ContestStatus status, Instant startAt, Instant endAt) {
        ContestEntity e = new ContestEntity();
        e.setId(id);
        e.setName("考试A");
        e.setStatus(status.name());
        e.setStartAt(startAt);
        e.setEndAt(endAt);
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void createInsertsDraftWithGeneratedId() {
        service.create("考试A", "描述", Instant.now(), Instant.now().plusSeconds(3600), "user_1");

        ArgumentCaptor<ContestEntity> captor = ArgumentCaptor.forClass(ContestEntity.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNotBlank();
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("user_1");
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        when(mapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(ContestException.NotFound.class);
    }

    @Test
    void reviewTransitionsDraftToReviewing() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.DRAFT, null, null));
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.submitForReview("c1").getStatus()).isEqualTo("REVIEWING");
    }

    @Test
    void publishRequiresConfiguredTimeWindow() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.REVIEWING, null, null));

        assertThatThrownBy(() -> service.publish("c1"))
                .isInstanceOf(ContestException.PublishIncomplete.class)
                .hasMessageContaining("时间窗口");
    }

    @Test
    void publishTransitionsReviewingToPublished() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.REVIEWING,
                Instant.now(), Instant.now().plusSeconds(3600)));
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.publish("c1").getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentTransitionThrowsWhenPreStateAlreadyChanged() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.REVIEWING,
                Instant.now(), Instant.now().plusSeconds(3600)));
        // 条件更新未命中：前态已被其他请求改走
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.publish("c1"))
                .isInstanceOf(ContestException.InvalidTransition.class);
    }

    @Test
    void archiveTransitionsFinishedToArchived() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.FINISHED, null, null));
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.archive("c1").getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void archiveRunningContestRejected() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.RUNNING, null, null));

        assertThatThrownBy(() -> service.archive("c1"))
                .isInstanceOf(ContestException.InvalidTransition.class);
    }

    @Test
    void illegalTransitionRejectedBeforeUpdate() {
        when(mapper.selectById("c1")).thenReturn(entity("c1", ContestStatus.DRAFT, null, null));

        assertThatThrownBy(() -> service.publish("c1"))
                .isInstanceOf(ContestException.InvalidTransition.class);
    }
}
