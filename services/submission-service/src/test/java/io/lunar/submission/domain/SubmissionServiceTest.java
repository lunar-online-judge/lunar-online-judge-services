package io.lunar.submission.domain;

import io.lunar.submission.domain.dto.SubmissionView;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionServiceTest {

    private final SubmissionMapper submissionMapper = mock(SubmissionMapper.class);
    private final OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final SubmissionService service = new SubmissionService(submissionMapper, outboxMapper, upstream);

    @BeforeAll
    static void initMpMetadata() {
        for (Class<?> entityType : List.of(SubmissionEntity.class, OutboxEventEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @BeforeEach
    void happyPathUpstream() {
        when(submissionMapper.selectOne(any())).thenReturn(null);
        when(upstream.checkSession(any(), any())).thenReturn(Map.of(
                "canSubmit", true, "sessionStatus", "ACTIVE", "contestStatus", "RUNNING", "remainingSeconds", 3600));
        when(upstream.currentProblemVersion(any(), any())).thenReturn(Map.of(
                "versionId", "v1", "allowedLanguages", List.of("cpp", "c"),
                "timeLimitMs", 1000, "memoryLimitKb", 131072));
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
    }

    @Test
    void idempotentReplayReturnsFirstResult() {
        // 同键同指纹：直接返回首次结果，不再写库
        String fingerprint = sha256("p1|cpp|" + sha256("int main(){}"));
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
    void inactiveSessionRejected() {
        when(upstream.checkSession(any(), any())).thenReturn(Map.of(
                "canSubmit", false, "sessionStatus", "NONE", "contestStatus", "RUNNING", "remainingSeconds", 0));

        assertThatThrownBy(() -> service.submit("c1", "p1", "cpp", "x", null, null, "stu_1", "Bearer t"))
                .isInstanceOf(SubmissionException.NotSubmittable.class);
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

        SubmissionView view = service.applyJudgment("sub_existing", "ACCEPTED", 100, 3,
                3, "trace-2");

        assertThat(view.status()).isEqualTo("ACCEPTED");
        assertThat(view.score()).isEqualTo(100);
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("lunar-submission-judged");
    }

    @Test
    void judgmentOnTerminalStatusRejected() {
        SubmissionEntity submission = existing(null);
        submission.setStatus("ACCEPTED");
        when(submissionMapper.selectById("sub_existing")).thenReturn(submission);

        assertThatThrownBy(() -> service.applyJudgment("sub_existing", "WRONG_ANSWER", 0, 0, 3, null))
                .isInstanceOf(SubmissionException.InvalidStatusTransition.class);
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
