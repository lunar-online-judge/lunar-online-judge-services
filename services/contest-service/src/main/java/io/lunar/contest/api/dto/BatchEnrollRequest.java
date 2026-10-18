package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 批量加入既有学生：从共享学生目录选人，不再临时创建账号。 */
public record BatchEnrollRequest(
        @NotEmpty(message = "至少选择一名学生")
        @Size(max = 200, message = "一次最多加入 200 名学生")
        List<String> studentIds) {
}
