package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.AnnouncementView;
import io.lunar.contest.infra.AnnouncementConfirmEntity;
import io.lunar.contest.infra.AnnouncementConfirmMapper;
import io.lunar.contest.infra.AnnouncementEntity;
import io.lunar.contest.infra.AnnouncementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考试公告（docs/exam-process.md §8）：
 * 发布（老师/管理员）；考生拉取列表即送达（require_confirmation 的公告记录 delivered_at）；
 * 考生显式确认记录 confirmed_at；重复确认幂等（保留首次确认时间）。
 * 公告默认不改变考试时间。
 */
@Component
public class AnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final AnnouncementConfirmMapper confirmMapper;
    private final ContestService contestService;

    public AnnouncementService(AnnouncementMapper announcementMapper,
                               AnnouncementConfirmMapper confirmMapper,
                               ContestService contestService) {
        this.announcementMapper = announcementMapper;
        this.confirmMapper = confirmMapper;
        this.contestService = contestService;
    }

    /** 发布公告：考试必须存在；任意生命周期状态均可发布（考前通知、考中提醒都合法）。 */
    public AnnouncementView publish(String contestId, String content, boolean important,
                                    boolean requireConfirmation, String operatorId) {
        contestService.get(contestId);
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setId(UUID.randomUUID().toString());
        announcement.setContestId(contestId);
        announcement.setContent(content);
        announcement.setImportant(important);
        announcement.setRequireConfirmation(requireConfirmation);
        announcement.setCreatedBy(operatorId);
        announcement.setCreatedAt(Instant.now());
        announcementMapper.insert(announcement);
        return toView(announcement, null, null);
    }

    /** 老师/管理员视角：全部公告，不含送达/确认信息。 */
    public List<AnnouncementView> listForOperator(String contestId) {
        return announcementMapper.selectList(new LambdaQueryWrapper<AnnouncementEntity>()
                        .eq(AnnouncementEntity::getContestId, contestId)
                        .orderByDesc(AnnouncementEntity::getCreatedAt))
                .stream().map(announcement -> toView(announcement, null, null)).toList();
    }

    /** 考生视角：拉取即送达（require_confirmation 的公告记录 delivered_at），并附带自己的确认时间。 */
    public List<AnnouncementView> listForCandidate(String contestId, String studentId) {
        List<AnnouncementEntity> announcements = announcementMapper.selectList(
                new LambdaQueryWrapper<AnnouncementEntity>()
                        .eq(AnnouncementEntity::getContestId, contestId)
                        .orderByDesc(AnnouncementEntity::getCreatedAt));
        if (announcements.isEmpty()) {
            return List.of();
        }
        Map<String, AnnouncementConfirmEntity> confirms = confirmMapper.selectList(
                        new LambdaQueryWrapper<AnnouncementConfirmEntity>()
                                .eq(AnnouncementConfirmEntity::getStudentId, studentId)
                                .in(AnnouncementConfirmEntity::getAnnouncementId,
                                        announcements.stream().map(AnnouncementEntity::getId).toList()))
                .stream()
                .collect(Collectors.toMap(AnnouncementConfirmEntity::getAnnouncementId, Function.identity()));

        List<AnnouncementView> views = new ArrayList<>();
        for (AnnouncementEntity announcement : announcements) {
            AnnouncementConfirmEntity confirm = confirms.get(announcement.getId());
            Instant deliveredAt = confirm == null ? null : confirm.getDeliveredAt();
            Instant confirmedAt = confirm == null ? null : confirm.getConfirmedAt();
            if (confirm == null && Boolean.TRUE.equals(announcement.getRequireConfirmation())) {
                // 首次拉取即送达
                confirm = new AnnouncementConfirmEntity();
                confirm.setId(UUID.randomUUID().toString());
                confirm.setAnnouncementId(announcement.getId());
                confirm.setStudentId(studentId);
                confirm.setDeliveredAt(Instant.now());
                confirmMapper.insert(confirm);
                deliveredAt = confirm.getDeliveredAt();
            }
            views.add(toView(announcement, deliveredAt, confirmedAt));
        }
        return views;
    }

    /** 考生确认公告：记录 confirmed_at；重复确认幂等（保留首次）。公告须存在且属于该竞赛。 */
    public void confirm(String contestId, String announcementId, String studentId) {
        AnnouncementEntity announcement = announcementMapper.selectById(announcementId);
        if (announcement == null || !contestId.equals(announcement.getContestId())) {
            throw new ContestException.NotFound();
        }
        AnnouncementConfirmEntity confirm = confirmMapper.selectOne(
                new LambdaQueryWrapper<AnnouncementConfirmEntity>()
                        .eq(AnnouncementConfirmEntity::getAnnouncementId, announcementId)
                        .eq(AnnouncementConfirmEntity::getStudentId, studentId)
                        .last("limit 1"));
        if (confirm == null) {
            confirm = new AnnouncementConfirmEntity();
            confirm.setId(UUID.randomUUID().toString());
            confirm.setAnnouncementId(announcementId);
            confirm.setStudentId(studentId);
            confirm.setDeliveredAt(Instant.now());
            confirm.setConfirmedAt(Instant.now());
            confirmMapper.insert(confirm);
        } else if (confirm.getConfirmedAt() == null) {
            confirm.setConfirmedAt(Instant.now());
            confirmMapper.updateById(confirm);
        }
    }

    private AnnouncementView toView(AnnouncementEntity announcement, Instant deliveredAt, Instant confirmedAt) {
        return new AnnouncementView(announcement.getId(), announcement.getContent(),
                Boolean.TRUE.equals(announcement.getImportant()),
                Boolean.TRUE.equals(announcement.getRequireConfirmation()),
                announcement.getCreatedBy(), announcement.getCreatedAt(), deliveredAt, confirmedAt);
    }
}
