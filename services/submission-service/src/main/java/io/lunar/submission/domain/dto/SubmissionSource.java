package io.lunar.submission.domain.dto;

/** 提交源码：只对教师/管理员开放，用于阅卷与代码查看。 */
public record SubmissionSource(String language, String sourceCode) {
}
