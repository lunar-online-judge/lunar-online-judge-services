package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import io.lunar.contest.domain.ExamSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 考生会话入口（docs/exam-process.md §6）：
 * 只有考生本人能进入/心跳（studentId = JWT sub）；老师/管理员不走此入口（监考视图后续落地）。
 * 进入与心跳都幂等：重复进入恢复同一会话并刷新心跳。
 */
@RestController
@RequestMapping("/api/v1/contests/{contestId}/session")
public class SessionController {

    private final ExamSessionService examSessionService;

    public SessionController(ExamSessionService examSessionService) {
        this.examSessionService = examSessionService;
    }

    /** 进入/恢复考试会话：校验名单、竞赛状态与时间窗口；返回 sessionId 与剩余秒数。 */
    @PostMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<ExamSessionService.SessionView>> enter(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ExamSessionService.SessionView view = examSessionService.enter(contestId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }

    /** 心跳：刷新最后心跳时间，返回剩余秒数；会话非 ACTIVE 返回 409 SESSION_NOT_ACTIVE。 */
    @PostMapping("/heartbeat")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<ExamSessionService.SessionView>> heartbeat(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ExamSessionService.SessionView view = examSessionService.heartbeat(contestId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(requestId, view));
    }
}
