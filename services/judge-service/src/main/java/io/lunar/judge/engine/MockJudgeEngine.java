package io.lunar.judge.engine;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Mock 引擎：模拟"提交后立即判对"，供无 go-judge 时的全链路演示
 * （MQ 闭环：submission.created → judge → judged → submission 状态更新）。
 * 真实引擎通过 GO_JUDGE_BASE_URL 启用 GoJudgeAdapter（优先级更高）。
 *
 * 与 GoJudgeAdapter 同构：submit 时按 engineId 记住测试点数，query 时连同 verdict 一起返回，
 * 让"按测试点通过比例给分"的链路在 mock 下也能走通。
 */
@Component
@Profile("mock")
public class MockJudgeEngine implements JudgeEngine {

    private final Map<String, Integer> caseCounts = new ConcurrentHashMap<>();

    @Override
    public JudgeSubmissionResult submit(JudgeSubmissionCommand command) {
        String engineId = "mock-" + UUID.randomUUID();
        // testFiles 里每个测试点是一对 <name>.in / <name>.out
        caseCounts.put(engineId, command.testFiles() == null ? 0 : command.testFiles().size() / 2);
        return new JudgeSubmissionResult(engineId, "FINISHED");
    }

    @Override
    public JudgeResult query(String engineSubmissionId) {
        int totalCases = caseCounts.getOrDefault(engineSubmissionId, 0);
        return new JudgeResult(engineSubmissionId, "ACCEPTED", 10L, 65536L, "mock 评测通过",
                totalCases, totalCases);
    }

    @Override
    public void cancel(String engineSubmissionId) {
        caseCounts.remove(engineSubmissionId);
    }
}
