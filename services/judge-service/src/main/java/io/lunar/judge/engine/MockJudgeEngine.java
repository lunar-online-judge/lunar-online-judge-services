package io.lunar.judge.engine;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 本地 Mock 引擎：模拟"提交后立即判对"，供无 go-judge 时的全链路演示
 * （MQ 闭环：submission.created → judge → judged → submission 状态更新）。
 * 真实引擎通过 GO_JUDGE_BASE_URL 启用 GoJudgeAdapter（优先级更高）。
 */
@Component
@Profile("mock")
public class MockJudgeEngine implements JudgeEngine {

    @Override
    public JudgeSubmissionResult submit(JudgeSubmissionCommand command) {
        return new JudgeSubmissionResult("mock-" + UUID.randomUUID(), "FINISHED");
    }

    @Override
    public JudgeResult query(String engineSubmissionId) {
        // 模拟判对：verdict 与耗时/内存一并返回，评分由 judge 侧映射（ACCEPTED=100）
        return new JudgeResult(engineSubmissionId, "ACCEPTED", 10L, 65536L, "mock 评测通过");
    }

    @Override
    public void cancel(String engineSubmissionId) {
    }
}
