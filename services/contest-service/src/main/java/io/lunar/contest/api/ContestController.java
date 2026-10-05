package io.lunar.contest.api;

import io.lunar.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {
    private final ConcurrentHashMap<UUID, ContestResponse> contests = new ConcurrentHashMap<>();

    @PostMapping
    public ApiResponse<ContestResponse> create(@Valid @RequestBody CreateContestRequest request,
                                               @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        ContestResponse result = new ContestResponse(UUID.randomUUID(), request.name(), request.startAt(), request.endAt(), "DRAFT");
        contests.put(result.id(), result);
        return ApiResponse.success(requestId, result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ContestResponse> get(@PathVariable UUID id,
                                           @RequestHeader(value = "X-Request-Id", defaultValue = "local") String requestId) {
        ContestResponse result = contests.get(id);
        return result == null ? ApiResponse.failure(requestId, "CONTEST_NOT_FOUND", "contest does not exist") : ApiResponse.success(requestId, result);
    }

    public record CreateContestRequest(@NotBlank String name, @NotNull Instant startAt, @NotNull Instant endAt) {}
    public record ContestResponse(UUID id, String name, Instant startAt, Instant endAt, String status) {}
}
