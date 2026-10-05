package io.lunar.contracts.judge;

import java.util.Map;

public record GoJudgeSubmissionRequest(String cmd, Map<String, String> files, long timeLimitMs, long memoryLimitKb) {}
