package io.lunar.contest.domain;

import io.lunar.contest.infra.AnnouncementConfirmEntity;
import io.lunar.contest.infra.AnnouncementConfirmMapper;
import io.lunar.contest.infra.AnnouncementEntity;
import io.lunar.contest.infra.AnnouncementMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnouncementServiceTest {

    private final AnnouncementMapper announcementMapper = mock(AnnouncementMapper.class);
    private final AnnouncementConfirmMapper confirmMapper = mock(AnnouncementConfirmMapper.class);
    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final AnnouncementService service = new AnnouncementService(
            announcementMapper, confirmMapper, new ContestService(contestMapper));

    static {
        // LambdaQueryWrapper 依赖 MP 的实体元数据缓存；纯单测需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AnnouncementConfirmEntity.class);
    }

    private static AnnouncementEntity announcement(String id, boolean requireConfirmation) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setId(id);
        announcement.setContestId("c1");
        announcement.setContent("注意考试时间");
        announcement.setImportant(true);
        announcement.setRequireConfirmation(requireConfirmation);
        announcement.setCreatedBy("teacher_1");
        announcement.setCreatedAt(Instant.now());
        return announcement;
    }

    @BeforeEach
    void contestExists() {
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        when(contestMapper.selectById("c1")).thenReturn(contest);
        when(contestMapper.selectById("nope")).thenReturn(null);
    }

    @Test
    void publishInsertsAnnouncementWithOperator() {
        AnnouncementService.AnnouncementView view = service.publish("c1", "注意考试时间", true, true, "teacher_1");

        ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
        verify(announcementMapper).insert(captor.capture());
        assertThat(captor.getValue().getImportant()).isTrue();
        assertThat(captor.getValue().getRequireConfirmation()).isTrue();
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("teacher_1");
        assertThat(view.requireConfirmation()).isTrue();
    }

    @Test
    void publishUnknownContestThrowsNotFound() {
        assertThatThrownBy(() -> service.publish("nope", "x", false, false, "teacher_1"))
                .isInstanceOf(ContestException.NotFound.class);
    }

    @Test
    void candidateListDeliversRequireConfirmationAnnouncements() {
        when(announcementMapper.selectList(any())).thenReturn(List.of(
                announcement("a1", true), announcement("a2", false)));
        when(confirmMapper.selectList(any())).thenReturn(List.of());

        List<AnnouncementService.AnnouncementView> views = service.listForCandidate("c1", "stu_1");

        assertThat(views).hasSize(2);
        // 仅 require_confirmation 的公告记录送达
        ArgumentCaptor<AnnouncementConfirmEntity> captor = ArgumentCaptor.forClass(AnnouncementConfirmEntity.class);
        verify(confirmMapper).insert((AnnouncementConfirmEntity) captor.capture());
        assertThat(captor.getValue().getAnnouncementId()).isEqualTo("a1");
        assertThat(captor.getValue().getDeliveredAt()).isNotNull();
        assertThat(captor.getValue().getConfirmedAt()).isNull();
    }

    @Test
    void alreadyDeliveredAnnouncementNotReDelivered() {
        AnnouncementEntity a1 = announcement("a1", true);
        when(announcementMapper.selectList(any())).thenReturn(List.of(a1));
        AnnouncementConfirmEntity delivered = new AnnouncementConfirmEntity();
        delivered.setAnnouncementId("a1");
        delivered.setStudentId("stu_1");
        delivered.setDeliveredAt(Instant.now());
        when(confirmMapper.selectList(any())).thenReturn(List.of(delivered));

        List<AnnouncementService.AnnouncementView> views = service.listForCandidate("c1", "stu_1");

        verify(confirmMapper, never()).insert(any(AnnouncementConfirmEntity.class));
        assertThat(views.get(0).deliveredAt()).isEqualTo(delivered.getDeliveredAt());
    }

    @Test
    void confirmInsertsRecordWithTimestamps() {
        when(announcementMapper.selectById("a1")).thenReturn(announcement("a1", true));
        when(confirmMapper.selectOne(any())).thenReturn(null);

        service.confirm("c1", "a1", "stu_1");

        ArgumentCaptor<AnnouncementConfirmEntity> captor = ArgumentCaptor.forClass(AnnouncementConfirmEntity.class);
        verify(confirmMapper).insert((AnnouncementConfirmEntity) captor.capture());
        assertThat(captor.getValue().getDeliveredAt()).isNotNull();
        assertThat(captor.getValue().getConfirmedAt()).isNotNull();
    }

    @Test
    void repeatedConfirmIsIdempotent() {
        when(announcementMapper.selectById("a1")).thenReturn(announcement("a1", true));
        AnnouncementConfirmEntity confirmed = new AnnouncementConfirmEntity();
        confirmed.setAnnouncementId("a1");
        confirmed.setStudentId("stu_1");
        confirmed.setDeliveredAt(Instant.now());
        confirmed.setConfirmedAt(Instant.now());
        when(confirmMapper.selectOne(any())).thenReturn(confirmed);

        service.confirm("c1", "a1", "stu_1");

        // 已确认过的不再更新，保留首次确认时间
        verify(confirmMapper, never()).updateById(any(AnnouncementConfirmEntity.class));
    }

    @Test
    void confirmAnnouncementOfOtherContestThrowsNotFound() {
        when(announcementMapper.selectById("a1")).thenReturn(announcement("a1", true));
        // 该公告属于 c1，查询 c2 → 不属于 → NotFound
        assertThatThrownBy(() -> service.confirm("c2", "a1", "stu_1"))
                .isInstanceOf(ContestException.NotFound.class);
    }
}
