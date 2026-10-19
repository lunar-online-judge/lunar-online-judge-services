package io.lunar.submission.domain.dto;

import java.util.List;

/** 提交分页列表。 */
public record SubmissionPage(List<SubmissionSummary> items, long total, long page, long pageSize) {
}
