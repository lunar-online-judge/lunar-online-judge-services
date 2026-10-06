package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.domain.ContestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * 考试生命周期入口（docs/exam-process.md §1）：
 * 老师创建/提交审核，管理员审核发布；角色校验在路由层，资源范围校验随名单/授权功能继续细化。
 */
@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {

    private final ContestService contestService;

    public ContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> create(@Valid @RequestBody CreateContestRequest request,
                                                               Authentication authentication,
                                                               @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestEntity entity = contestService.create(request.name(), request.description(),
                request.startAt(), request.endAt(), authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(entity)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContestResponse>> get(@PathVariable String id,
                                                            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.get(id))));
    }

    /** 提交管理员审核：DRAFT -> REVIEWING。 */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> review(@PathVariable String id,
                                                               @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.submitForReview(id))));
    }

    /** 管理员审核通过并发布：REVIEWING -> PUBLISHED。 */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> publish(@PathVariable String id,
                                                                @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        return ResponseEntity.ok(ApiResponse.success(requestId, ContestResponse.of(contestService.publish(id))));
    }

    public record CreateContestRequest(@NotBlank String name, String description,
                                       Instant startAt, Instant endAt) {}

    public record ContestResponse(String id, String name, String description, String status,
                                  Instant startAt, Instant endAt, String createdBy,
                                  Instant createdAt, Instant updatedAt) {
        static ContestResponse of(ContestEntity e) {
            return new ContestResponse(e.getId(), e.getName(), e.getDescription(), e.getStatus(),
                    e.getStartAt(), e.getEndAt(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }
}
