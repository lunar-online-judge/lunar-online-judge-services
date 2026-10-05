package io.lunar.judge.engine;

import io.lunar.common.judge.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("mock")
public class MockJudgeEngine implements JudgeEngine {
    @Override public JudgeSubmissionResult submit(JudgeSubmissionCommand command) {
        return new JudgeSubmissionResult("mock-" + UUID.randomUUID(), "QUEUED");
    }
    @Override public JudgeResult query(String engineSubmissionId) {
        return new JudgeResult(engineSubmissionId, "PENDING", null, null, null);
    }
    @Override public void cancel(String engineSubmissionId) { }
}
