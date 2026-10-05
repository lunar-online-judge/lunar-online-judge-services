package io.lunar.problem.api;

import io.lunar.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {
    private final ConcurrentHashMap<UUID, ProblemResponse> problems = new ConcurrentHashMap<>();

    @PostMapping
    public ApiResponse<ProblemResponse> create(@Valid @RequestBody CreateProblemRequest request,
                                               @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        ProblemResponse result = new ProblemResponse(UUID.randomUUID(), request.title(), request.statement(), request.defaultLanguage());
        problems.put(result.id(), result);
        return ApiResponse.success(requestId, result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProblemResponse> get(@PathVariable UUID id,
                                           @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        ProblemResponse result = problems.get(id);
        return result == null ? ApiResponse.failure(requestId, "PROBLEM_NOT_FOUND", "problem does not exist") : ApiResponse.success(requestId, result);
    }

    public record CreateProblemRequest(@NotBlank String title, @NotBlank String statement, @NotBlank String defaultLanguage) {}
    public record ProblemResponse(UUID id, String title, String statement, String defaultLanguage) {}
}
