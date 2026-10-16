package io.lunar.ai.infra.dto;

/**
 * 题目版本详情（problem-service 的服务间契约，调用方按字段名取值）。
 *
 * 只声明 AI 用得到的字段：Jackson 默认忽略未知字段，因此上游给全量版本信息也不会出错；
 * 反过来把所有字段抄一遍，会在上游加字段时形成"两边都要改"的假耦合。
 *
 * **刻意不需要测试点**：AI 评审看题面即可，测试数据不进入提示词（避免把测试用例泄露给模型/云厂商）。
 */
public record ProblemVersionDetail(String versionId,
                                   String problemId,
                                   String title,
                                   String statement,
                                   String sampleInput,
                                   String sampleOutput,
                                   Integer timeLimitMs,
                                   Integer memoryLimitKb,
                                   Integer outputLimitKb) {
}
