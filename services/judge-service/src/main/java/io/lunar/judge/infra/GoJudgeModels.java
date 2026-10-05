package io.lunar.judge.infra;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * go-judge REST API 协议模型（POST /run）。
 * 字段名与 https://docs.goj.ac/api 对齐，禁改：单位为 cpuLimit/clockLimit=纳秒、memoryLimit=字节。
 */
final class GoJudgeModels {

    private GoJudgeModels() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunRequest(String requestId, List<Cmd> cmd) {}

    /** fd 描述：stdin 用 content，stdout/stderr 用 name+max 采集到结果 files。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Fd(String name, Long max, String content) {}

    /** copyIn 源：content 与 fileId 二选一（fileId 来自上一步 copyOutCached）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CopyInFile(String content, String fileId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Cmd(
            List<String> args,
            List<String> env,
            List<Fd> files,
            Long cpuLimit,
            Long clockLimit,
            Long memoryLimit,
            Integer procLimit,
            Map<String, CopyInFile> copyIn,
            List<String> copyOut,
            List<String> copyOutCached) {}

    /** status 为字符串枚举：Accepted / Time Limit Exceeded / Memory Limit Exceeded /
     *  Output Limit Exceeded / File Error / Nonzero Exit Status / Signalled / Internal Error。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunResult(
            String status,
            String error,
            Integer exitStatus,
            Long time,
            Long memory,
            Long runTime,
            Map<String, String> files,
            Map<String, String> fileIds) {}
}
