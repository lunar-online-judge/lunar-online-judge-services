package io.lunar.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求生成出题草稿。
 *
 * {@code contestId} 可选：给了就按该考试的归属做教师范围判断（BE-01）；
 * 不给则要求 ADMIN（无归属的产出对老师不可见，与既有约定一致）。
 *
 * 需求描述有长度上限：它会被拼进提示词，超长输入既烧配额又容易让模型跑偏。
 */
public record ProblemDraftJobRequest(String contestId,
                                     @NotBlank @Size(max = 4000) String requirement,
                                     String difficulty,
                                     String languageHint) {
}
