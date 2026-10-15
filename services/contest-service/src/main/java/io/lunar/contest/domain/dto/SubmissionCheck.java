package io.lunar.contest.domain.dto;

/** 服务间提交校验结果（供 submission-service）：canSubmit 为会话、时间窗口、竞赛状态、名单四项的合成判定。 */
public record SubmissionCheck(boolean canSubmit, String sessionStatus, String contestStatus,
                              long remainingSeconds) {}
