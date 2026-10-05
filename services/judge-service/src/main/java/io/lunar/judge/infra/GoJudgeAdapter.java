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
    private static final List<String> SANDBOX_ENV = List.of("PATH=/usr/local/bin:/usr/bin:/bin");

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
        } catch (Exception e) {
            return systemError(engineId, "go-judge 调用失败: " + e.getMessage());
        }
    }

    private JudgeResult runTestcases(String engineId, LanguageTemplate template,
                                     JudgeSubmissionCommand command, List<TestCase> testcases) {
        String programFileId = null;
        if (template.compileArgs != null) {
            GoJudgeModels.RunResult compile = transport.run(new GoJudgeModels.RunRequest(engineId,
                    List.of(compileCmd(template, command.sourceCode())))).get(0);
            programFileId = compile.fileIds() == null ? null
                    : compile.fileIds().get(template.compileOutputs.get(0));
            if (!"Accepted".equals(compile.status()) || programFileId == null) {
                return new JudgeResult(engineId, "COMPILE_ERROR", null, null,
                        truncate(compileStderr(compile)));
            }
        }

        int passed = 0;
        Long maxTimeNs = null;
        Long maxMemoryBytes = null;
        for (int i = 0; i < testcases.size(); i++) {
            TestCase tc = testcases.get(i);
            GoJudgeModels.RunResult r = transport.run(new GoJudgeModels.RunRequest(engineId,
                    List.of(runCmd(template, command, tc, programFileId)))).get(0);
            maxTimeNs = max(maxTimeNs, r.time());
            maxMemoryBytes = max(maxMemoryBytes, r.memory());
            String fail = failVerdictOf(r, tc);
            if (fail != null) {
                String detail = "测试点 " + (i + 1) + "：" + failDetailOf(fail, r) + "（通过 "
                        + passed + "/" + testcases.size() + "）";
                return new JudgeResult(engineId, fail, timeMsOf(maxTimeNs), memoryKbOf(maxMemoryBytes),
                        truncate(detail));
            }
            passed++;
        }
        return new JudgeResult(engineId, "ACCEPTED", timeMsOf(maxTimeNs), memoryKbOf(maxMemoryBytes),
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
                                     TestCase tc, String programFileId) {
        Map<String, GoJudgeModels.CopyInFile> copyIn = template.compileArgs != null
                ? Map.of(template.compileOutputs.get(0), new GoJudgeModels.CopyInFile(null, programFileId))
                : Map.of(template.sourceFileName, new GoJudgeModels.CopyInFile(command.sourceCode(), null));
        long cpuMs = Math.min(command.cpuTimeLimitMs(), MAX_CPU_MS);
        // clockLimit 要比 cpuLimit 宽裕，避免调度抖动把正常程序判成超时
        long clockNs = (cpuMs + 1000) * 1_000_000L;
        return new GoJudgeModels.Cmd(template.runArgs, SANDBOX_ENV,
                List.of(stdinFd(tc.input()), stdoutFd(), stderrFd()),
                cpuMs * 1_000_000L, clockNs, command.memoryLimitKb() * 1024L, RUN_PROC_LIMIT,
                copyIn, null, null);
    }

    /** go-judge 状态 → 平台 verdict；返回 null 表示该测试点通过。 */
    private String failVerdictOf(GoJudgeModels.RunResult r, TestCase tc) {
        return switch (r.status() == null ? "" : r.status()) {
            case "Accepted" -> matches(r.files() == null ? null : r.files().get("stdout"), tc.expected())
                    ? null : "WRONG_ANSWER";
            case "Time Limit Exceeded" -> "TLE";
            case "Memory Limit Exceeded" -> "MLE";
            case "Output Limit Exceeded" -> "OLE";
            case "Nonzero Exit Status", "Signalled" -> "RE";
            default -> "SYSTEM_ERROR";
        };
    }

    private String failDetailOf(String verdict, GoJudgeModels.RunResult r) {
        return switch (verdict) {
            case "WRONG_ANSWER" -> "输出不匹配";
            case "TLE" -> "超时（cpu " + timeMsOf(r.time()) + "ms）";
            case "MLE" -> "超内存（" + memoryKbOf(r.memory()) + "KB）";
            case "OLE" -> "输出超限";
            case "RE" -> "运行时错误（exit=" + r.exitStatus() + "）" + stderrSnippet(r);
            default -> "沙箱异常: " + r.status() + (r.error() == null ? "" : " " + r.error());
        };
    }

    private String compileStderr(GoJudgeModels.RunResult r) {
        String stderr = r.files() == null ? null : r.files().get("stderr");
        if (stderr == null || stderr.isBlank()) {
            stderr = r.error() == null ? "" : r.error();
        }
        return "编译失败：" + stderr.strip();
    }

    private String stderrSnippet(GoJudgeModels.RunResult r) {
        String stderr = r.files() == null ? null : r.files().get("stderr");
        return stderr == null || stderr.isBlank() ? "" : "：" + stderr.strip();
    }

    private JudgeResult systemError(String engineId, String message) {
        return new JudgeResult(engineId, "SYSTEM_ERROR", null, null, truncate(message));
    }

    /**
     * 测试点文件约定：<name>.in 为输入，与同名的 <name>.out / <name>.ans 为期望输出，按名排序；
     * 没有配对输出的输入文件忽略。
     */
    private List<TestCase> parseTestCases(Map<String, String> testFiles) {
        if (testFiles == null || testFiles.isEmpty()) {
            return List.of();
        }
        Map<String, String> expected = new HashMap<>();
        Map<String, String> inputs = new TreeMap<>();
        for (Map.Entry<String, String> e : testFiles.entrySet()) {
            String name = e.getKey();
            if (name.endsWith(".out") || name.endsWith(".ans")) {
                expected.put(baseName(name), e.getValue());
            } else if (name.endsWith(".in")) {
                inputs.put(name, e.getValue());
            }
        }
        List<TestCase> cases = new ArrayList<>();
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            String exp = expected.get(baseName(e.getKey()));
            if (exp != null) {
                cases.add(new TestCase(e.getValue(), exp));
            }
        }
        return List.copyOf(cases);
    }

    /** 比较前归一化：统一换行、去每行行尾空白、去结尾空行（标准 OJ 宽松匹配）。 */
    static boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    static String normalize(String s) {
        String[] lines = (s == null ? "" : s).replace("\r\n", "\n").replace('\r', '\n')
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

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }

    private record TestCase(String input, String expected) {}

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static Long max(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private static Long timeMsOf(Long timeNs) {
        return timeNs == null ? null : timeNs / 1_000_000L;
    }

    private static Long memoryKbOf(Long memoryBytes) {
        return memoryBytes == null ? null : memoryBytes / 1024L;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_MESSAGE_LENGTH ? s : s.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private static GoJudgeModels.Fd stdinFd(String content) {
        return new GoJudgeModels.Fd(null, null, content);
    }

    private static GoJudgeModels.Fd stdoutFd() {
        return new GoJudgeModels.Fd("stdout", OUTPUT_LIMIT_BYTES, null);
    }

    private static GoJudgeModels.Fd stderrFd() {
        return new GoJudgeModels.Fd("stderr", OUTPUT_LIMIT_BYTES, null);
    }
}
