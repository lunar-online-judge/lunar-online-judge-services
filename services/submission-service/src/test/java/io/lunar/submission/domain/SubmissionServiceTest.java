package io.lunar.submission.domain;

import io.lunar.common.audit.AuditRecorder;
import io.lunar.submission.config.SubmissionLimitProperties;
import io.lunar.submission.domain.dto.SubmissionView;
import io.lunar.submission.infra.ConsumedEventMapper;
import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import io.lunar.submission.infra.SubmissionEntity;
import io.lunar.submission.infra.SubmissionMapper;
import io.lunar.submission.infra.UpstreamClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionServiceTest {

    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
    private final ConsumedEventMapper consumedEventMapper = mock(ConsumedEventMapper.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final SubmissionService service = new SubmissionService(
            submissionMapper, outboxMapper, consumedEventMapper, upstream, limitProperties(), auditRecorder);

    private static SubmissionLimitProperties limitProperties() {
        SubmissionLimitProperties properties = new SubmissionLimitProperties();
        properties.setWindowSeconds(60);
        properties.setMaxPerWindow(30);
        return properties;
    }

    @BeforeAll
    static void initMpMetadata() {
        for (Class<?> entityType : List.of(SubmissionEntity.class, OutboxEventEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @BeforeEach
    void happyPathUpstream() {
        when(submissionMapper.selectOne(any())).thenReturn(null);
        // 频率限制默认未触发（窗口内 0 次提交），超限用例单独覆盖
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        // 幂等闸门默认"首次见到该 eventId"（返回 1）；重复投递用例单独覆盖为 0。
        // 必须显式打桩：Mockito 对 int 返回值的默认值是 0，那会被读成"已处理过"而让所有用例短路。
        when(consumedEventMapper.tryMarkProcessed(any(), any())).thenReturn(1);
        // 判决回写是条件更新（CAS）：默认成功（影响 1 行），并发冲突用例单独覆盖为 0 行
        when(submissionMapper.update(isNull(), any())).thenReturn(1);
        when(upstream.checkSession(any(), any())).thenReturn(Map.of(
                "canSubmit", true, "sessionStatus", "ACTIVE", "contestStatus", "RUNNING", "remainingSeconds", 3600));
        when(upstream.currentProblemVersion(any(), any())).thenReturn(Map.of(
                "versionId", "v1", "allowedLanguages", List.of("cpp", "c"),
                "timeLimitMs", 1000, "memoryLimitKb", 131072));
        when(upstream.problemTestcases(any(), any())).thenReturn(List.of(
                Map.of("name", "case1", "inputContent", "1 2", "expectedOutput", "3")));
        // BE-04：版本以考试锁定值为准，判题限制取自该版本
        when(upstream.lockedProblemVersion(any(), any(), any())).thenReturn("v1");
        when(upstream.problemVersion(any(), any())).thenReturn(Map.of("timeLimitMs", 1000, "memoryLimitKb", 131072));
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SubmissionEntity existing(String fingerprint) {
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId("sub_existing");
        submission.setContestId("c1");
        submission.setProblemId("p1");
        submission.setStudentId("stu_1");
        submission.setLanguage("cpp");
        submission.setStatus("QUEUED");
        submission.setRequestFingerprint(fingerprint);
        submission.setCreatedAt(Instant.now());
        return submission;
    }

    @Test
    void submitWritesSubmissionAndOutboxInOneCall() {
        SubmissionView view = service.submit("c1", "p1", "cpp", "int main(){}",
                "key-1", "trace-1", "stu_1", "Bearer t");

        assertThat(view.status()).isEqualTo("QUEUED");
        assertThat(view.submissionId()).isNotBlank();
        verify(submissionMapper).insert(any(SubmissionEntity.class));
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("lunar-submission-created");
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().getPayload()).contains("\"problemVersionId\":\"v1\"");
        // 测试点随 created 事件送到评测引擎（键名与 GoJudgeAdapter.parseTestCases 约定一致）
        assertThat(captor.getValue().getPayload()).contains("\"case1.in\":\"1 2\"").contains("\"case1.out\":\"3\"");
        // 提交动作写审计账本（尽力而为；traceId 透传给账本，链路不断）
        verify(auditRecorder).record("SUBMISSION_CREATED", "SUBMISSION", view.submissionId(), "c1",
                "题目 p1 语言 cpp", "trace-1", "Bearer t");
    }

    /** BE-04：提交用的版本是**考试锁定版本**，不是题目当前发布版本（否则考试中途发布会改掉测试数据）。 */
    @Test
    void submitUsesContestLockedProblemVersion() {
        when(upstream.currentProblemVersion(any(), any())).thenReturn(Map.of(
                "versionId", "v-current", "allowedLanguages", List.of("cpp", "c"),
                "timeLimitMs", 1000, "memoryLimitKb", 131072));
        when(upstream.lockedProblemVersion("c1", "p1", "Bearer t")).thenReturn("v-locked");

        service.submit("c1", "p1", "cpp", "int main(){}", "key-9", "trace-9", "stu_1", "Bearer t");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"problemVersionId\":\"v-locked\"");
    }

    @Test
    void idempotentReplayReturnsFirstResult() {
        // 同键同指纹：直接返回首次结果，不再写库
        // 指纹含 contestId（BE-04）：同一个幂等键不能跨考试复用
        String fingerprint = sha256("c1|p1|cpp|" + sha256("int main(){}"));
        when(submissionMapper.selectOne(any())).thenReturn(existing(fingerprint));

        SubmissionView view = service.submit("c1", "p1", "cpp", "int main(){}", "key-1", null,
                "stu_1", "Bearer t");

        assertThat(view.submissionId()).isEqualTo("sub_existing");
        verify(submissionMapper, never()).insert(any(SubmissionEntity.class));
        verify(outboxMapper, never()).insert(any(OutboxEventEntity.class));
    }

    @Test
    void sameKeyDifferentPayloadConflicts() {
        when(submissionMapper.selectOne(any())).thenReturn(existing("other-fingerprint"));

        assertThatThrownBy(() -> service.submit("c1", "p1", "cpp", "int main(){}", "key-1", null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.Duplicate.class);
    }

    @Test
    void rateLimitExceededWhenWindowIsFull() {
        // 校验五（exam-process §7）：窗口内已达上限 → 429，且不落库、不发事件
        when(submissionMapper.selectCount(any())).thenReturn(30L);

        assertThatThrownBy(() -> service.submit("c1", "p1", "cpp", "x", null, null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.LimitExceeded.class)
                .extracting(exception -> ((SubmissionException) exception).code())
                .isEqualTo("SUBMISSION_LIMIT_EXCEEDED");
        verify(submissionMapper, never()).insert(any(SubmissionEntity.class));
        verify(outboxMapper, never()).insert(any(OutboxEventEntity.class));
    }

    @Test
    void inactiveSessionRejected() {
        when(upstream.checkSession(any(), any())).thenReturn(Map.of(
                "canSubmit", false, "sessionStatus", "NONE", "contestStatus", "RUNNING", "remainingSeconds", 0));

        // 会话未激活用注册表里的 SESSION_NOT_ACTIVE，而不是"不在名单"（CONTEST_NOT_ENROLLED）
        assertThatThrownBy(() -> service.submit("c1", "p1", "cpp", "x", null, null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.SessionNotActive.class)
                .extracting(exception -> ((SubmissionException) exception).code())
                .isEqualTo("SESSION_NOT_ACTIVE");
    }

    @Test
    void contestNotRunningRejected() {
        when(upstream.checkSession(any(), any())).thenReturn(Map.of(
                "canSubmit", false, "sessionStatus", "ACTIVE", "contestStatus", "FINISHED", "remainingSeconds", 0));

        assertThatThrownBy(() -> service.submit("c1", "p1", "cpp", "x", null, null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.ContestNotRunning.class);
    }

    @Test
    void languageNotInWhitelistRejected() {
        assertThatThrownBy(() -> service.submit("c1", "p1", "java", "x", null, null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.LanguageNotAllowed.class);
    }

    @Test
    void judgmentUpdatesStatusAndWritesOutbox() {
        SubmissionEntity submission = existing(null);
        when(submissionMapper.selectById("sub_existing")).thenReturn(submission);

        SubmissionView view = service.applyJudgment("sub_existing", "evt-judged-1", "ACCEPTED", 100, 3,
                3, "trace-2");

        assertThat(view.status()).isEqualTo("ACCEPTED");
        assertThat(view.score()).isEqualTo(100);
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("lunar-submission-judged");
        // 判决回写由 MQ 消费者触发（无用户令牌）→ 账本走服务身份，operatorToken 传 null
        verify(auditRecorder).record(eq("SUBMISSION_JUDGED"), eq("SUBMISSION"), eq("sub_existing"),
                any(), eq("verdict=ACCEPTED score=100"), eq("trace-2"), isNull());
    }

    @Test
    void judgmentOnTerminalStatusRejected() {
        SubmissionEntity submission = existing(null);
        submission.setStatus("ACCEPTED");
        when(submissionMapper.selectById("sub_existing")).thenReturn(submission);

        assertThatThrownBy(() -> service.applyJudgment("sub_existing", "evt-judged-2", "WRONG_ANSWER", 0, 0, 3, null))
                .isInstanceOf(SubmissionException.InvalidStatusTransition.class);
    }

    @Test
    void judgmentLosesConditionalUpdateOnConcurrentWrite() {
        // 并发：读到 QUEUED，但写入时状态已被另一条判决改走（影响 0 行）→ 409，且不再补发 judged 事件
        SubmissionEntity accepted = existing(null);
        accepted.setStatus("ACCEPTED");
        when(submissionMapper.selectById("sub_existing"))
                .thenReturn(existing(null))
                .thenReturn(accepted);
        when(submissionMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.applyJudgment("sub_existing", "evt-judged-3", "WRONG_ANSWER", 0, 0, 3, "trace-3"))
                .isInstanceOf(SubmissionException.InvalidStatusTransition.class);
        verify(outboxMapper, never()).insert(any(OutboxEventEntity.class));
    }

    /** 幂等闸门：同一 eventId 第二次到达时不得再改库、也不得再补发 judged 事件。 */
    @Test
    void duplicateJudgedEventIsSkippedByConsumedEventsGate() {
        when(consumedEventMapper.tryMarkProcessed("evt-dup", "submission_judged_group")).thenReturn(0);
        when(submissionMapper.selectById("sub_existing")).thenReturn(existing(null));

        SubmissionView view = service.applyJudgment("sub_existing", "evt-dup", "ACCEPTED", 100, 3, 3, null);

        assertThat(view.status()).isEqualTo("QUEUED");
        verify(submissionMapper, never()).update(isNull(), any());
        verify(outboxMapper, never()).insert(any(OutboxEventEntity.class));
    }

    /** 旧格式消息（无 eventId）不过闸门：退化为终态保护（CAS），仍正常回写。 */
    @Test
    void legacyMessageWithoutEventIdSkipsTheGate() {
        when(submissionMapper.selectById("sub_existing")).thenReturn(existing(null));

        service.applyJudgment("sub_existing", null, "ACCEPTED", 100, 3, 3, null);

        verify(consumedEventMapper, never()).tryMarkProcessed(any(), any());
        verify(submissionMapper).update(isNull(), any());
    }

    @Test
    void studentCannotReadOthersSubmission() {
        SubmissionEntity submission = existing(null);
        when(submissionMapper.selectById("sub_existing")).thenReturn(submission);

        assertThatThrownBy(() -> service.get("sub_existing", "stu_2", false))
                .isInstanceOf(SubmissionException.NotFound.class);
        // 老师/管理员可读
        assertThat(service.get("sub_existing", "teacher_1", true).submissionId()).isEqualTo("sub_existing");
    }
}
