package io.lunar.judge.mq;

import io.lunar.common.event.EventEnvelope;
import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import io.lunar.judge.infra.ConsumedEventGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * judged 事件的发布契约，三件事必须同时成立：
 * ① verdict 用 JudgeVerdict 的契约值（引擎的历史短名 TLE/RE 与 PENDING 一律兜底 SYSTEM_ERROR）；
 * ② 消息体是带 eventId 的完整信封——消费端按 eventId 幂等全靠它；
 * ③ 内层 payload 富化提交身份（contestId/problemId/problemVersionId/studentId/language），
 *    这是 analytics 读模型建立"考试×学生×题目"事实表的唯一来源（judge 没有数据库）。
 * 另外兼容旧的裸 payload 形态：生产者与消费者无法同时发布，灰度期内两种格式共存。
 */
class SubmissionCreatedConsumerTest {

    private static final String JUDGED_EVENT_TYPE = "lunar-submission-judged";

    /** 旧格式：整条消息就是内层 payload（灰度期内的存量消息）。 */
    private static final String LEGACY_BARE_CREATED_PAYLOAD =
            "{\"submissionId\":\"sub_1\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\"}";

    private final JudgeEngine judgeEngine = mock(JudgeEngine.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final ConsumedEventGuard consumedEventGuard = mock(ConsumedEventGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubmissionCreatedConsumer consumer =
            new SubmissionCreatedConsumer(judgeEngine, rocketMQTemplate, consumedEventGuard);

    @Test
    void knownVerdictsArePublishedAsContractValues() throws Exception {
        assertThat(judgedPayloadOf(judgedMessageFor("TIME_LIMIT_EXCEEDED")).get("verdict").asText())
                .isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(judgedPayloadOf(judgedMessageFor("RUNTIME_ERROR")).get("verdict").asText())
                .isEqualTo("RUNTIME_ERROR");
        JsonNode accepted = judgedPayloadOf(judgedMessageFor("ACCEPTED"));
        assertThat(accepted.get("verdict").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("score").asInt()).isEqualTo(100);
    }

    @Test
    void unknownOrNotReadyVerdictsFallBackToSystemError() throws Exception {
        assertThat(judgedPayloadOf(judgedMessageFor("TLE")).get("verdict").asText()).isEqualTo("SYSTEM_ERROR");
        assertThat(judgedPayloadOf(judgedMessageFor("RE")).get("verdict").asText()).isEqualTo("SYSTEM_ERROR");
        assertThat(judgedPayloadOf(judgedMessageFor("PENDING")).get("verdict").asText()).isEqualTo("SYSTEM_ERROR");
        assertThat(judgedPayloadOf(judgedMessageFor(null)).get("verdict").asText()).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    void testFilesFromPayloadReachTheEngine() {
        stubEngine("ACCEPTED", 1, 1);
        clearInvocations(rocketMQTemplate);

        consumer.onMessage("{\"submissionId\":\"sub_1\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\","
                + "\"testFiles\":{\"case1.in\":\"1 2\",\"case1.out\":\"3\"}}");

        ArgumentCaptor<JudgeSubmissionCommand> commandCaptor = ArgumentCaptor.forClass(JudgeSubmissionCommand.class);
        verify(judgeEngine).submit(commandCaptor.capture());
        assertThat(commandCaptor.getValue().testFiles())
                .containsEntry("case1.in", "1 2")
                .containsEntry("case1.out", "3");
    }

    @Test
    void scoreIsProportionalToPassedTestcases() throws Exception {
        // docs/exam-process.md §7：通过全部测试点得满分，否则按测试点通过比例给部分分
        JsonNode partial = judgedPayloadOf(judgedMessageFor("WRONG_ANSWER", 1, 3));
        assertThat(partial.get("score").asInt()).isEqualTo(33);
        assertThat(partial.get("passedCases").asInt()).isEqualTo(1);
        assertThat(partial.get("totalCases").asInt()).isEqualTo(3);
        assertThat(judgedPayloadOf(judgedMessageFor("ACCEPTED", 3, 3)).get("score").asInt()).isEqualTo(100);
        assertThat(judgedPayloadOf(judgedMessageFor("COMPILE_ERROR", 0, 3)).get("score").asInt()).isZero();
    }

    /** ② 信封：eventId 必须随消息上线，否则下游只能靠业务终态兜底、无法真正幂等。 */
    @Test
    void judgedMessageCarriesEnvelopeWithEventId() throws Exception {
        Message<String> judgedMessage = judgedMessageFor("ACCEPTED");
        JsonNode envelope = objectMapper.readTree(judgedMessage.getPayload());

        assertThat(envelope.get(EventEnvelope.FIELD_EVENT_ID).asText()).isNotBlank();
        assertThat(envelope.get(EventEnvelope.FIELD_EVENT_TYPE).asText()).isEqualTo(JUDGED_EVENT_TYPE);
        assertThat(envelope.get(EventEnvelope.FIELD_AGGREGATE_ID).asText()).isEqualTo("sub_1");
        assertThat(envelope.get(EventEnvelope.FIELD_OCCURRED_AT).asText()).isNotBlank();
        // RocketMQ 的 KEYS 头与信封 eventId 一致，运维按 ID 查消息时两者能对上
        assertThat(judgedMessage.getHeaders().get("KEYS"))
                .isEqualTo(envelope.get(EventEnvelope.FIELD_EVENT_ID).asText());
    }

    /** ③ 富化：从 created 事件把提交身份原样带到 judged，analytics 才可能独立消费这条事件。 */
    @Test
    void judgedPayloadCarriesSubmissionIdentityForDownstreamReadModels() throws Exception {
        stubEngine("ACCEPTED", 1, 1);
        clearInvocations(rocketMQTemplate);

        consumer.onMessage(envelopedCreatedMessage("evt-created-1"));

        JsonNode judgedPayload = judgedPayloadOf(capturedJudgedMessage());
        assertThat(judgedPayload.get("contestId").asText()).isEqualTo("contest-9");
        assertThat(judgedPayload.get("problemId").asText()).isEqualTo("problem-7");
        assertThat(judgedPayload.get("problemVersionId").asText()).isEqualTo("version-3");
        assertThat(judgedPayload.get("studentId").asText()).isEqualTo("student-5");
        assertThat(judgedPayload.get("language").asText()).isEqualTo("cpp");
        // 提交时间取 created 事件的 occurredAt（服务端受理提交的时刻），不是 judged 时刻
        assertThat(judgedPayload.get("submittedAt").asText()).isEqualTo("2026-10-13T10:00:00Z");
        // traceId 由信封透传到 judged，判题链路不再断链
        assertThat(judgedPayload.get("traceId").asText()).isEqualTo("trace-created-1");
        assertThat(objectMapper.readTree(capturedJudgedMessage().getPayload())
                .get(EventEnvelope.FIELD_TRACE_ID).asText()).isEqualTo("trace-created-1");
    }

    /** 旧格式仍必须能消费：切换期间存量消息与新消息会同时到达同一个消费组。 */
    @Test
    void legacyBareCreatedPayloadIsStillAccepted() throws Exception {
        assertThat(judgedPayloadOf(judgedMessageFor("ACCEPTED")).get("verdict").asText()).isEqualTo("ACCEPTED");
    }

    /** 去重闸门命中时不得评测、不得发消息——这是"重复投递不重复评测"的落点。 */
    @Test
    void alreadyProcessedEventIsSkippedWithoutEvaluating() throws Exception {
        when(consumedEventGuard.isProcessed("evt-created-dup")).thenReturn(true);

        consumer.onMessage(envelopedCreatedMessage("evt-created-dup"));

        verify(judgeEngine, never()).submit(any());
        verifyNoInteractions(rocketMQTemplate);
    }

    /** 登记发生在处理成功之后：中途失败必须能重投重评，否则判决会永久丢失。 */
    @Test
    void successfulEvaluationMarksEventProcessed() throws Exception {
        stubEngine("ACCEPTED", 1, 1);
        clearInvocations(rocketMQTemplate);

        consumer.onMessage(envelopedCreatedMessage("evt-created-2"));

        verify(consumedEventGuard).markProcessed("evt-created-2");
    }

    /** 带信封的 created 消息：eventId 是去重闸门的键。 */
    private String envelopedCreatedMessage(String eventId) throws Exception {
        return EventEnvelope.of(eventId, "lunar-submission-created", "sub_1",
                        Instant.parse("2026-10-13T10:00:00Z"), EventEnvelope.CURRENT_SCHEMA_VERSION, "trace-created-1",
                        objectMapper.readTree("{\"submissionId\":\"sub_1\",\"contestId\":\"contest-9\","
                                + "\"problemId\":\"problem-7\",\"problemVersionId\":\"version-3\","
                                + "\"studentId\":\"student-5\",\"language\":\"cpp\",\"sourceCode\":\"int main(){}\"}"))
                .toJson(objectMapper);
    }

    private Message<String> judgedMessageFor(String engineVerdict) throws Exception {
        return judgedMessageFor(engineVerdict, null, null);
    }

    private Message<String> judgedMessageFor(String engineVerdict, Integer passedCases, Integer totalCases)
            throws Exception {
        stubEngine(engineVerdict, passedCases, totalCases);
        // 同一个测试方法里会多次调用本方法，先清掉上一次的投递记录
        clearInvocations(rocketMQTemplate);
        consumer.onMessage(LEGACY_BARE_CREATED_PAYLOAD);
        return capturedJudgedMessage();
    }

    private void stubEngine(String engineVerdict, Integer passedCases, Integer totalCases) {
        when(judgeEngine.submit(any())).thenReturn(new JudgeSubmissionResult("engine-1", "FINISHED"));
        when(judgeEngine.query("engine-1")).thenReturn(
                new JudgeResult("engine-1", engineVerdict, 12L, 2048L, "engine message", passedCases, totalCases));
    }

    @SuppressWarnings("unchecked")
    private Message<String> capturedJudgedMessage() {
        ArgumentCaptor<Message<String>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq(JUDGED_EVENT_TYPE), messageCaptor.capture());
        return messageCaptor.getValue();
    }

    private JsonNode judgedPayloadOf(Message<String> judgedMessage) throws Exception {
        return objectMapper.readTree(judgedMessage.getPayload()).get(EventEnvelope.FIELD_PAYLOAD);
    }
}
