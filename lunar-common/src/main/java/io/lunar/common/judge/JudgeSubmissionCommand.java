package io.lunar.common.judge;

import java.util.Map;

public record JudgeSubmissionCommand(
        String submissionId,
        String language,
        String sourceCode,
        Map<String, String> testFiles,
        long cpuTimeLimitMs,
        long memoryLimitKb) {
}
