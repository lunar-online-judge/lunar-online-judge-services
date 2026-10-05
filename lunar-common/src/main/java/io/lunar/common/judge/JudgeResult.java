package io.lunar.common.judge;

public record JudgeResult(String engineSubmissionId, String verdict, Long timeMs, Long memoryKb, String message) {}
