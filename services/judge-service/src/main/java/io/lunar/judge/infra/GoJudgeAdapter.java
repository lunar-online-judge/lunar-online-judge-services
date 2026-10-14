package io.lunar.judge.infra;

import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeResult;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * go-judge 引擎适配器：把 {@link JudgeSubmissionCommand} 翻译为 go-judge REST 调用，
 * 归一化 verdict 后缓存，供 query 读取。go-judge 的 /run 是同步执行，因此 submit 一次性
 * 完成"编译 + 全部测试点"，返回 FINISHED；异步任务状态机由评测链路（judge_task）在后续实现。
 *
 * 激活条件：GO_JUDGE_BASE_URL 已设置（@Primary 使其优先于 mock profile 的 MockJudgeEngine）。
 */
@Component
@Primary
@ConditionalOnProperty("GO_JUDGE_BASE_URL")
public class GoJudgeAdapter implements JudgeEngine {

    private static final long COMPILE_CPU_LIMIT_NS = 30_000_000_000L;
    private static final long COMPILE_MEMORY_LIMIT_BYTES = 1024L * 1024 * 1024;
    private static final long OUTPUT_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_CPU_MS = 300_000;
    private static final int RUN_PROC_LIMIT = 128;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long BYTES_PER_KB = 1024L;
    private static final List<String> SANDBOX_ENV = List.of("PATH=/usr/local/bin:/usr/bin:/bin");

    /** clockLimit 相对 cpuLimit 的宽裕量（毫秒），避免调度抖动把正常程序判成超时。 */
    private static final long CLOCK_LIMIT_GRACE_MS = 1000;

    /** go-judge fd 采集名：stdout/stderr 内容经 files 回传。 */
    private static final String FD_STDOUT = "stdout";
    private static final String FD_STDERR = "stderr";

    /** go-judge /run 响应 status 取值（协议字符串，禁改）。 */
    private static final String GO_JUDGE_ACCEPTED = "Accepted";
    private static final String GO_JUDGE_TIME_LIMIT_EXCEEDED = "Time Limit Exceeded";
    private static final String GO_JUDGE_MEMORY_LIMIT_EXCEEDED = "Memory Limit Exceeded";
    private static final String GO_JUDGE_OUTPUT_LIMIT_EXCEEDED = "Output Limit Exceeded";
    private static final String GO_JUDGE_NONZERO_EXIT_STATUS = "Nonzero Exit Status";
    private static final String GO_JUDGE_SIGNALLED = "Signalled";

    /** 平台 verdict 码（下发给 submission-service 的契约值，禁改）。 */
    private static final String VERDICT_ACCEPTED = "ACCEPTED";
    private static final String VERDICT_WRONG_ANSWER = "WRONG_ANSWER";
    private static final String VERDICT_COMPILE_ERROR = "COMPILE_ERROR";
    private static final String VERDICT_TLE = "TLE";
    private static final String VERDICT_MLE = "MLE";
    private static final String VERDICT_OLE = "OLE";
    private static final String VERDICT_RE = "RE";
    private static final String VERDICT_SYSTEM_ERROR = "SYSTEM_ERROR";

    private final GoJudgeTransport transport;
    private final Map<String, JudgeResult> results = new ConcurrentHashMap<>();

    public GoJudgeAdapter(
            @Value("${GO_JUDGE_BASE_URL:http://localhost:5050}") String baseUrl,
            @Value("${GO_JUDGE_CONNECT_TIMEOUT_MS:2000}") long connectTimeoutMs,
            @Value("${GO_JUDGE_READ_TIMEOUT_MS:10000}") long readTimeoutMs) {
        this(new RestGoJudgeTransport(baseUrl, connectTimeoutMs, readTimeoutMs));
    }

    GoJudgeAdapter(GoJudgeTransport transport) {
        this.transport = transport;
    }

    @Override
    public JudgeSubmissionResult submit(JudgeSubmissionCommand command) {
        String engineId = "gojudge-" + UUID.randomUUID();
        results.put(engineId, execute(engineId, command));
        return new JudgeSubmissionResult(engineId, "FINISHED");
    }

    @Override
    public JudgeResult query(String engineSubmissionId) {
        return results.getOrDefault(engineSubmissionId,
                new JudgeResult(engineSubmissionId, "PENDING", null, null, null));
    }

    @Override
    public void cancel(String engineSubmissionId) {
        results.remove(engineSubmissionId);
    }

    private JudgeResult execute(String engineId, JudgeSubmissionCommand command) {
        LanguageTemplate template = LanguageTemplate.of(command.language());
        if (template == null) {
            return systemError(engineId, "不支持的语言: " + command.language());
        }
        List<TestCase> testcases = parseTestCases(command.testFiles());
        if (testcases.isEmpty()) {
            return systemError(engineId, "提交缺少测试点：testFiles 需要 <name>.in / <name>.out 成对出现");
        }
        try {
            return runTestcases(engineId, template, command, testcases);
        } catch (Exception exception) {
            // 整段调用边界的兜底：任何底层异常都归一化为 SYSTEM_ERROR，消息携带原始异常信息
            return systemError(engineId, "go-judge 调用失败: " + exception.getMessage());
        }
    }

    private JudgeResult runTestcases(String engineId, LanguageTemplate template,
                                     JudgeSubmissionCommand command, List<TestCase> testcases) {
        String programFileId = null;
        if (template.compileArgs != null) {
            GoJudgeModels.RunResult compileResult = transport.run(new GoJudgeModels.RunRequest(engineId,
                    List.of(compileCmd(template, command.sourceCode())))).get(0);
            programFileId = compileResult.fileIds() == null ? null
                    : compileResult.fileIds().get(template.compileOutputs.get(0));
            if (!GO_JUDGE_ACCEPTED.equals(compileResult.status()) || programFileId == null) {
                return new JudgeResult(engineId, VERDICT_COMPILE_ERROR, null, null,
                        truncate(compileStderr(compileResult)));
            }
        }

        int passed = 0;
        Long maxTimeNs = null;
        Long maxMemoryBytes = null;
        for (int i = 0; i < testcases.size(); i++) {
            TestCase testcase = testcases.get(i);
            GoJudgeModels.RunResult runResult = transport.run(new GoJudgeModels.RunRequest(engineId,
                    List.of(runCmd(template, command, testcase, programFileId)))).get(0);
            maxTimeNs = max(maxTimeNs, runResult.time());
            maxMemoryBytes = max(maxMemoryBytes, runResult.memory());
            String failVerdict = failVerdictOf(runResult, testcase);
            if (failVerdict != null) {
                String detail = "测试点 " + (i + 1) + "：" + failDetailOf(failVerdict, runResult) + "（通过 "
                        + passed + "/" + testcases.size() + "）";
                return new JudgeResult(engineId, failVerdict, timeMsOf(maxTimeNs), memoryKbOf(maxMemoryBytes),
                        truncate(detail));
            }
            passed++;
        }
        return new JudgeResult(engineId, VERDICT_ACCEPTED, timeMsOf(maxTimeNs), memoryKbOf(maxMemoryBytes),
                "通过 " + passed + "/" + testcases.size() + " 测试点");
    }

    private GoJudgeModels.Cmd compileCmd(LanguageTemplate template, String sourceCode) {
        return new GoJudgeModels.Cmd(template.compileArgs, SANDBOX_ENV,
                List.of(stdinFd(""), stdoutFd(), stderrFd()),
                COMPILE_CPU_LIMIT_NS, COMPILE_CPU_LIMIT_NS * 2, COMPILE_MEMORY_LIMIT_BYTES, null,
                Map.of(template.sourceFileName, new GoJudgeModels.CopyInFile(sourceCode, null)),
                null, template.compileOutputs);
    }

    private GoJudgeModels.Cmd runCmd(LanguageTemplate template, JudgeSubmissionCommand command,
                                     TestCase testcase, String programFileId) {
        Map<String, GoJudgeModels.CopyInFile> copyIn = template.compileArgs != null
                ? Map.of(template.compileOutputs.get(0), new GoJudgeModels.CopyInFile(null, programFileId))
                : Map.of(template.sourceFileName, new GoJudgeModels.CopyInFile(command.sourceCode(), null));
        long cpuMs = Math.min(command.cpuTimeLimitMs(), MAX_CPU_MS);
        long clockNs = (cpuMs + CLOCK_LIMIT_GRACE_MS) * NANOS_PER_MILLI;
        return new GoJudgeModels.Cmd(template.runArgs, SANDBOX_ENV,
                List.of(stdinFd(testcase.input()), stdoutFd(), stderrFd()),
                cpuMs * NANOS_PER_MILLI, clockNs, command.memoryLimitKb() * BYTES_PER_KB, RUN_PROC_LIMIT,
                copyIn, null, null);
    }

    /** go-judge 状态 → 平台 verdict；返回 null 表示该测试点通过。 */
    private String failVerdictOf(GoJudgeModels.RunResult runResult, TestCase testcase) {
        return switch (runResult.status() == null ? "" : runResult.status()) {
            case GO_JUDGE_ACCEPTED -> matches(runResult.files() == null ? null
                    : runResult.files().get(FD_STDOUT), testcase.expected()) ? null : VERDICT_WRONG_ANSWER;
            case GO_JUDGE_TIME_LIMIT_EXCEEDED -> VERDICT_TLE;
            case GO_JUDGE_MEMORY_LIMIT_EXCEEDED -> VERDICT_MLE;
            case GO_JUDGE_OUTPUT_LIMIT_EXCEEDED -> VERDICT_OLE;
            case GO_JUDGE_NONZERO_EXIT_STATUS, GO_JUDGE_SIGNALLED -> VERDICT_RE;
            default -> VERDICT_SYSTEM_ERROR;
        };
    }

    private String failDetailOf(String verdict, GoJudgeModels.RunResult runResult) {
        return switch (verdict) {
            case VERDICT_WRONG_ANSWER -> "输出不匹配";
            case VERDICT_TLE -> "超时（cpu " + timeMsOf(runResult.time()) + "ms）";
            case VERDICT_MLE -> "超内存（" + memoryKbOf(runResult.memory()) + "KB）";
            case VERDICT_OLE -> "输出超限";
            case VERDICT_RE -> "运行时错误（exit=" + runResult.exitStatus() + "）" + stderrSnippet(runResult);
            default -> "沙箱异常: " + runResult.status()
                    + (runResult.error() == null ? "" : " " + runResult.error());
        };
    }

    private String compileStderr(GoJudgeModels.RunResult runResult) {
        String stderr = runResult.files() == null ? null : runResult.files().get(FD_STDERR);
        if (stderr == null || stderr.isBlank()) {
            stderr = runResult.error() == null ? "" : runResult.error();
        }
        return "编译失败：" + stderr.strip();
    }

    private String stderrSnippet(GoJudgeModels.RunResult runResult) {
        String stderr = runResult.files() == null ? null : runResult.files().get(FD_STDERR);
        return stderr == null || stderr.isBlank() ? "" : "：" + stderr.strip();
    }

    private JudgeResult systemError(String engineId, String message) {
        return new JudgeResult(engineId, VERDICT_SYSTEM_ERROR, null, null, truncate(message));
    }

    /**
     * 测试点文件约定：<name>.in 为输入，与同名的 <name>.out / <name>.ans 为期望输出，按名排序；
     * 没有配对输出的输入文件忽略。
     */
    private List<TestCase> parseTestCases(Map<String, String> testFiles) {
        if (testFiles == null || testFiles.isEmpty()) {
            return List.of();
        }
        Map<String, String> expectedOutputs = new HashMap<>();
        Map<String, String> inputs = new TreeMap<>();
        for (Map.Entry<String, String> testFileEntry : testFiles.entrySet()) {
            String name = testFileEntry.getKey();
            if (name.endsWith(".out") || name.endsWith(".ans")) {
                expectedOutputs.put(baseName(name), testFileEntry.getValue());
            } else if (name.endsWith(".in")) {
                inputs.put(name, testFileEntry.getValue());
            }
        }
        List<TestCase> cases = new ArrayList<>();
        for (Map.Entry<String, String> inputEntry : inputs.entrySet()) {
            String expectedOutput = expectedOutputs.get(baseName(inputEntry.getKey()));
            if (expectedOutput != null) {
                cases.add(new TestCase(inputEntry.getValue(), expectedOutput));
            }
        }
        return List.copyOf(cases);
    }

    /** 比较前归一化：统一换行、去每行行尾空白、去结尾空行（标准 OJ 宽松匹配）。 */
    static boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    static String normalize(String text) {
        String[] lines = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            kept.add(stripTrailing(line));
        }
        int end = kept.size();
        while (end > 0 && kept.get(end - 1).isEmpty()) {
            end--;
        }
        return String.join("\n", kept.subList(0, end));
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(0, end);
    }

    private record TestCase(String input, String expected) {}

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static Long max(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private static Long timeMsOf(Long timeNs) {
        return timeNs == null ? null : timeNs / NANOS_PER_MILLI;
    }

    private static Long memoryKbOf(Long memoryBytes) {
        return memoryBytes == null ? null : memoryBytes / BYTES_PER_KB;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private static GoJudgeModels.Fd stdinFd(String content) {
        return new GoJudgeModels.Fd(null, null, content);
    }

    private static GoJudgeModels.Fd stdoutFd() {
        return new GoJudgeModels.Fd(FD_STDOUT, OUTPUT_LIMIT_BYTES, null);
    }

    private static GoJudgeModels.Fd stderrFd() {
        return new GoJudgeModels.Fd(FD_STDERR, OUTPUT_LIMIT_BYTES, null);
    }
}
