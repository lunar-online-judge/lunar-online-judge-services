package io.lunar.common.judge;

public interface JudgeEngine {
    JudgeSubmissionResult submit(JudgeSubmissionCommand command);
    JudgeResult query(String engineSubmissionId);
    void cancel(String engineSubmissionId);
}
