package io.lunar.submission.api;

import io.lunar.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {
    private final ConcurrentHashMap<UUID, SubmissionResponse> submissions = new ConcurrentHashMap<>();

    @PostMapping
    public ApiResponse<SubmissionResponse> create(@Valid @RequestBody CreateSubmissionRequest request,
                                                  @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        SubmissionResponse result = new SubmissionResponse(UUID.randomUUID(), request.contestId(), request.problemId(), request.language(), "QUEUED", Instant.now());
        submissions.put(result.id(), result);
        return ApiResponse.success(requestId, result);
    }

    @GetMapping("/{id}")
    public ApiResponse<SubmissionResponse> get(@PathVariable UUID id,
                                              @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        SubmissionResponse result = submissions.get(id);
        return result == null ? ApiResponse.failure(requestId, "SUBMISSION_NOT_FOUND", "submission does not exist") : ApiResponse.success(requestId, result);
    }

    public record CreateSubmissionRequest(UUID contestId, UUID problemId, @NotBlank String language, @NotBlank String sourceCode) {}
    public record SubmissionResponse(UUID id, UUID contestId, UUID problemId, String language, String status, Instant createdAt) {}
}
