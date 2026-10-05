package io.lunar.judge.infra;

import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoJudgeAdapterTest {

    private static final Map<String, String> TWO_CASES = Map.of(
            "a.in", "1", "a.out", "one",
            "b.in", "2", "b.out", "two");
    private static final Map<String, String> ONE_CASE = Map.of("a.in", "1", "a.out", "one");

    @Test
    void acceptedWhenAllTestcasesMatch() {
        StubTransport transport = transport(
                accepted(null, Map.of("main", "F1")),
                accepted(Map.of("stdout", "one"), null),
                accepted(Map.of("stdout", "two"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeSubmissionResult submit = adapter.submit(command("cpp", TWO_CASES));
        JudgeResult result = adapter.query(submit.engineSubmissionId());

        assertThat(submit.status()).isEqualTo("FINISHED");
        assertThat(submit.engineSubmissionId()).startsWith("gojudge-");
        assertThat(result.verdict()).isEqualTo("ACCEPTED");
        assertThat(result.message()).isEqualTo("通过 2/2 测试点");
        assertThat(transport.calls).isEqualTo(3);
    }

    @Test
    void compileErrorReportsCompilerStderr() {
        StubTransport transport = transport(new GoJudgeModels.RunResult(
                "Nonzero Exit Status", null, 1, 100L, 1000L, 200L,
                Map.of("stderr", "main.cpp:1:1: error: expected ';'"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(command("cpp", TWO_CASES)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo("COMPILE_ERROR");
        assertThat(result.message()).contains("error: expected ';'");
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void wrongAnswerStopsAtFirstFailingCase() {
        StubTransport transport = transport(
                accepted(null, Map.of("main", "F1")),
                accepted(Map.of("stdout", "one"), null),
                accepted(Map.of("stdout", "nope"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(command("cpp", TWO_CASES)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo("WRONG_ANSWER");
        assertThat(result.message()).contains("测试点 2").contains("通过 1/2");
    }

    @Test
    void goJudgeStatusesMapToPlatformVerdicts() {
        assertVerdict("Time Limit Exceeded", "TLE");
        assertVerdict("Memory Limit Exceeded", "MLE");
        assertVerdict("Output Limit Exceeded", "OLE");
        assertVerdict("Nonzero Exit Status", "RE");
        assertVerdict("Signalled", "RE");
        assertVerdict("File Error", "SYSTEM_ERROR");
        assertVerdict("Internal Error", "SYSTEM_ERROR");
    }

    private void assertVerdict(String goJudgeStatus, String expectedVerdict) {
        StubTransport transport = transport(
                accepted(null, Map.of("main", "F1")),
                new GoJudgeModels.RunResult(goJudgeStatus, "boom", 2, 100L, 1000L, 200L,
                        Map.of("stderr", "x"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(command("cpp", TWO_CASES)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo(expectedVerdict);
    }

    @Test
    void outputComparisonIgnoresTrailingWhitespaceAndBlankLines() {
        StubTransport transport = transport(
                accepted(null, Map.of("main", "F1")),
                accepted(Map.of("stdout", "one \r\n\r\n"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(
                command("cpp", ONE_CASE)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo("ACCEPTED");
    }

    @Test
    void interpretedLanguageRunsSourceDirectly() {
        StubTransport transport = transport(accepted(Map.of("stdout", "one"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(
                command("python", ONE_CASE)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo("ACCEPTED");
        // 解释型语言没有编译步骤：单个测试点只调用一次 /run
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void unsupportedLanguageAndMissingTestcasesAreSystemErrors() {
        GoJudgeAdapter adapter = new GoJudgeAdapter(new StubTransport(List.of()));

        JudgeResult unsupported = adapter.query(adapter.submit(
                command("ruby", TWO_CASES)).engineSubmissionId());
        JudgeResult noCases = adapter.query(adapter.submit(
                command("cpp", Map.of("a.in", "1"))).engineSubmissionId());

        assertThat(unsupported.verdict()).isEqualTo("SYSTEM_ERROR");
        assertThat(unsupported.message()).contains("ruby");
        assertThat(noCases.verdict()).isEqualTo("SYSTEM_ERROR");
        assertThat(noCases.message()).contains("测试点");
    }

    @Test
    void transportFailureBecomesSystemError() {
        GoJudgeAdapter adapter = new GoJudgeAdapter(new GoJudgeTransport() {
            @Override
            public List<GoJudgeModels.RunResult> run(GoJudgeModels.RunRequest request) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public String version() {
                return "test";
            }
        });

        JudgeResult result = adapter.query(adapter.submit(command("cpp", TWO_CASES)).engineSubmissionId());

        assertThat(result.verdict()).isEqualTo("SYSTEM_ERROR");
        assertThat(result.message()).contains("connection refused");
    }

    @Test
    void queryUnknownIdReturnsPendingAndCancelRemovesResult() {
        GoJudgeAdapter adapter = new GoJudgeAdapter(new StubTransport(List.of()));
        JudgeSubmissionResult submit = adapter.submit(command("ruby", TWO_CASES));

        assertThat(adapter.query("unknown").verdict()).isEqualTo("PENDING");

        adapter.cancel(submit.engineSubmissionId());
        assertThat(adapter.query(submit.engineSubmissionId()).verdict()).isEqualTo("PENDING");
    }

    @Test
    void timeAndMemoryAreNormalizedToMsAndKb() {
        StubTransport transport = transport(
                accepted(null, Map.of("main", "F1")),
                new GoJudgeModels.RunResult("Accepted", null, 0, 12_345_678L, 1024L * 2560, 20_000_000L,
                        Map.of("stdout", "one"), null));
        GoJudgeAdapter adapter = new GoJudgeAdapter(transport);

        JudgeResult result = adapter.query(adapter.submit(command("cpp", ONE_CASE)).engineSubmissionId());

        assertThat(result.timeMs()).isEqualTo(12L);
        assertThat(result.memoryKb()).isEqualTo(2560L);
    }

    private static JudgeSubmissionCommand command(String language, Map<String, String> testFiles) {
        return new JudgeSubmissionCommand("sub_1", language, "int main() { return 0; }",
                testFiles, 2000, 256000);
    }

    /** 每次 /run 调用对应一个响应批次。 */
    private static StubTransport transport(GoJudgeModels.RunResult... batches) {
        List<List<GoJudgeModels.RunResult>> list = new ArrayList<>(batches.length);
        for (GoJudgeModels.RunResult r : batches) {
            list.add(List.of(r));
        }
        return new StubTransport(list);
    }

    private static GoJudgeModels.RunResult accepted(Map<String, String> files, Map<String, String> fileIds) {
        return new GoJudgeModels.RunResult("Accepted", null, 0, 5_000_000L, 1024L * 1024, 8_000_000L,
                files, fileIds);
    }

    private static final class StubTransport implements GoJudgeTransport {
        private final List<List<GoJudgeModels.RunResult>> responses;
        private int calls;

        StubTransport(List<List<GoJudgeModels.RunResult>> batches) {
            this.responses = new ArrayList<>(batches);
        }

        @Override
        public List<GoJudgeModels.RunResult> run(GoJudgeModels.RunRequest request) {
            if (calls >= responses.size()) {
                throw new AssertionError("意外的第 " + (calls + 1) + " 次 /run 调用");
            }
            return responses.get(calls++);
        }

        @Override
        public String version() {
            return "test";
        }
    }
}
