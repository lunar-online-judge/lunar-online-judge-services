package io.lunar.judge.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/judge")
public class JudgeController {
    private final JudgeEngine judgeEngine;
    public JudgeController(JudgeEngine judgeEngine) { this.judgeEngine = judgeEngine; }

    @PostMapping("/submissions")
    public ApiResponse<JudgeSubmissionResult> submit(@RequestBody SubmitRequest request) {
        var result = judgeEngine.submit(new JudgeSubmissionCommand(request.submissionId(), request.language(), request.sourceCode(), Map.of(), request.cpuTimeLimitMs(), request.memoryLimitKb()));
        return ApiResponse.success("pending", result);
    }

    public record SubmitRequest(String submissionId, String language, String sourceCode, long cpuTimeLimitMs, long memoryLimitKb) {}
}
