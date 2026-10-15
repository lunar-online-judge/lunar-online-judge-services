package io.lunar.contest.domain.dto;

/**
 * 添加学生响应（docs/api.md"添加学生"）：不含密码或其哈希。
 * passwordChangeRequired 仅在本次调用新建了 auth 账号且 auth 统一下发临时密码时为 true；
 * 账号复用（学生已在其他竞赛报名）时为 false——本次没有下发任何密码。
 * 加入人/加入时间属名单查询契约（见 CandidateView），不在此响应中。
 */
public record EnrollmentResult(String studentId, String studentNo, String username, String displayName,
                               String enrollmentStatus, boolean passwordChangeRequired) {}
