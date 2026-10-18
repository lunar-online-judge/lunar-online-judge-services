package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.api.dto.CreatePlatformAnnouncementRequest;
import io.lunar.contest.domain.PlatformAnnouncementService;
import io.lunar.contest.domain.dto.PlatformAnnouncementPage;
import io.lunar.contest.domain.dto.PlatformAnnouncementView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台公告入口：管理员发布/删除，管理员与教师读取。
 * 与考试公告（/api/v1/contests/{contestId}/announcements）相互独立。
 */
@RestController
@RequestMapping("/api/v1/announcements")
public class PlatformAnnouncementController {

    private final PlatformAnnouncementService announcementService;

    public PlatformAnnouncementController(PlatformAnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<PlatformAnnouncementPage>> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                announcementService.list(page, pageSize)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PlatformAnnouncementView>> publish(
            @Valid @RequestBody CreatePlatformAnnouncementRequest request,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId,
                announcementService.publish(request.title(), request.content(),
                        request.important(), authentication.getName())));
    }

    @DeleteMapping("/{announcementId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String announcementId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        announcementService.delete(announcementId);
        return ResponseEntity.ok(ApiResponse.success(requestId, null));
    }
}
