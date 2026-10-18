package io.lunar.contest.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.lunar.contest.domain.dto.PlatformAnnouncementPage;
import io.lunar.contest.domain.dto.PlatformAnnouncementView;
import io.lunar.contest.infra.PlatformAnnouncementEntity;
import io.lunar.contest.infra.PlatformAnnouncementMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 平台公告（管理端全局公告）：管理员发布/删除，管理员与教师读取。
 * 与考试公告相互独立，不参与考试送达/确认流程。
 */
@Component
public class PlatformAnnouncementService {

    private static final long MAX_PAGE_SIZE = 100L;

    private final PlatformAnnouncementMapper announcementMapper;

    public PlatformAnnouncementService(PlatformAnnouncementMapper announcementMapper) {
        this.announcementMapper = announcementMapper;
    }

    /** 发布公告：记录发布人与发布时间，重要公告排在列表前面。 */
    public PlatformAnnouncementView publish(String title, String content, boolean important,
                                            String operatorId) {
        PlatformAnnouncementEntity announcement = new PlatformAnnouncementEntity();
        announcement.setId(UUID.randomUUID().toString());
        announcement.setTitle(title.trim());
        announcement.setContent(content.trim());
        announcement.setImportant(important);
        announcement.setCreatedBy(operatorId);
        announcement.setCreatedAt(Instant.now());
        announcementMapper.insert(announcement);
        return toView(announcement);
    }

    /** 分页列表：重要公告置顶，其余按发布时间倒序。 */
    public PlatformAnnouncementPage list(long page, long pageSize) {
        long safePage = Math.max(1L, page);
        long safePageSize = Math.min(MAX_PAGE_SIZE, Math.max(1L, pageSize));
        Page<PlatformAnnouncementEntity> result = announcementMapper.selectPage(
                new Page<>(safePage, safePageSize),
                new LambdaQueryWrapper<PlatformAnnouncementEntity>()
                        .orderByDesc(PlatformAnnouncementEntity::getImportant)
                        .orderByDesc(PlatformAnnouncementEntity::getCreatedAt));
        List<PlatformAnnouncementView> items = result.getRecords().stream()
                .map(this::toView)
                .toList();
        return new PlatformAnnouncementPage(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 删除公告：不存在按 404 处理。 */
    public void delete(String announcementId) {
        if (announcementMapper.deleteById(announcementId) == 0) {
            throw new ContestException.NotFound();
        }
    }

    private PlatformAnnouncementView toView(PlatformAnnouncementEntity announcement) {
        return new PlatformAnnouncementView(announcement.getId(), announcement.getTitle(),
                announcement.getContent(), Boolean.TRUE.equals(announcement.getImportant()),
                announcement.getCreatedBy(), announcement.getCreatedAt());
    }
}
