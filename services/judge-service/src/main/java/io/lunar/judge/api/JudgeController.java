package io.lunar.judge.api;

import io.lunar.common.api.ApiResponse;
import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import io.lunar.judge.api.dto.SubmitRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 判题 HTTP 入口：把提交转发给当前激活的 {@link JudgeEngine}，返回引擎提交句柄。 */
@RestController
@RequestMapping("/api/v1/judge")
public class JudgeController {

    private final JudgeEngine judgeEngine;

    public JudgeController(JudgeEngine judgeEngine) {
        this.judgeEngine = judgeEngine;
    }

    @PostMapping("/submissions")
    public ApiResponse<JudgeSubmissionResult> submit(@RequestBody SubmitRequest request) {
        JudgeSubmissionResult result = judgeEngine.submit(new JudgeSubmissionCommand(
                request.submissionId(), request.language(), request.sourceCode(),
                Map.of(), request.cpuTimeLimitMs(), request.memoryLimitKb()));
        return ApiResponse.success("pending", result);
    }
}
