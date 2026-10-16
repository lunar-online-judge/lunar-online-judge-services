package io.lunar.judge.mq;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题结果发布契约：verdict 必须是 JudgeVerdict 的契约值（长名），
 * 引擎侧的历史短名（TLE/MLE/OLE/RE）与未出结果的 PENDING 一律兜底 SYSTEM_ERROR。
 */
class SubmissionCreatedConsumerTest {

    private final JudgeEngine judgeEngine = mock(JudgeEngine.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final SubmissionCreatedConsumer consumer = new SubmissionCreatedConsumer(judgeEngine, rocketMQTemplate);

    @Test
    void knownVerdictsArePublishedAsContractValues() throws Exception {
        assertThat(judgedPayloadFor("TIME_LIMIT_EXCEEDED")).contains("\"verdict\":\"TIME_LIMIT_EXCEEDED\"");
        assertThat(judgedPayloadFor("RUNTIME_ERROR")).contains("\"verdict\":\"RUNTIME_ERROR\"");
        assertThat(judgedPayloadFor("ACCEPTED")).contains("\"verdict\":\"ACCEPTED\"").contains("\"score\":100");
    }

    @Test
    void unknownOrNotReadyVerdictsFallBackToSystemError() throws Exception {
        assertThat(judgedPayloadFor("TLE")).contains("\"verdict\":\"SYSTEM_ERROR\"");
        assertThat(judgedPayloadFor("RE")).contains("\"verdict\":\"SYSTEM_ERROR\"");
        assertThat(judgedPayloadFor("PENDING")).contains("\"verdict\":\"SYSTEM_ERROR\"");
        assertThat(judgedPayloadFor(null)).contains("\"verdict\":\"SYSTEM_ERROR\"");
    }

    @Test
    void testFilesFromPayloadReachTheEngine() throws Exception {
        when(judgeEngine.submit(any())).thenReturn(new JudgeSubmissionResult("engine-1", "FINISHED"));
        when(judgeEngine.query("engine-1")).thenReturn(new JudgeResult("engine-1", "ACCEPTED", 12L, 2048L, "ok", 1, 1));
        clearInvocations(rocketMQTemplate);

        consumer.onMessage("{\"submissionId\":\"sub_1\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\","
                + "\"testFiles\":{\"case1.in\":\"1 2\",\"case1.out\":\"3\"}}");

        ArgumentCaptor<JudgeSubmissionCommand> command = ArgumentCaptor.forClass(JudgeSubmissionCommand.class);
        verify(judgeEngine).submit(command.capture());
        assertThat(command.getValue().testFiles())
                .containsEntry("case1.in", "1 2")
                .containsEntry("case1.out", "3");
    }

    @Test
    void scoreIsProportionalToPassedTestcases() throws Exception {
        // docs/exam-process.md §7：通过全部测试点得满分，否则按测试点通过比例给部分分
        assertThat(judgedPayloadForCounts("WRONG_ANSWER", 1, 3))
                .contains("\"score\":33").contains("\"passedCases\":1").contains("\"totalCases\":3");
        assertThat(judgedPayloadForCounts("ACCEPTED", 3, 3)).contains("\"score\":100");
        assertThat(judgedPayloadForCounts("COMPILE_ERROR", 0, 3)).contains("\"score\":0");
    }

    /** 让引擎返回指定 verdict 与测试点数，返回真正投递出去的事件体。 */
    private String judgedPayloadForCounts(String engineVerdict, Integer passedCases, Integer totalCases)
            throws Exception {
        when(judgeEngine.submit(any())).thenReturn(new JudgeSubmissionResult("engine-1", "FINISHED"));
        when(judgeEngine.query("engine-1")).thenReturn(
                new JudgeResult("engine-1", engineVerdict, 12L, 2048L, "engine message", passedCases, totalCases));
        clearInvocations(rocketMQTemplate);

        consumer.onMessage("{\"submissionId\":\"sub_1\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\"}");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).syncSend(eq("lunar-submission-judged"), payload.capture());
        return payload.getValue().toString();
    }

    /** 让引擎返回指定 verdict，返回真正投递出去的事件体。 */
    private String judgedPayloadFor(String engineVerdict) throws Exception {
        when(judgeEngine.submit(any())).thenReturn(new JudgeSubmissionResult("engine-1", "FINISHED"));
        when(judgeEngine.query("engine-1"))
                .thenReturn(new JudgeResult("engine-1", engineVerdict, 12L, 2048L, "engine message"));
        // 同一个测试方法里会多次调用本方法，先清掉上一次的投递记录
        clearInvocations(rocketMQTemplate);

        consumer.onMessage("{\"submissionId\":\"sub_1\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\"}");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rocketMQTemplate).syncSend(eq("lunar-submission-judged"), payload.capture());
        return payload.getValue().toString();
    }
}
