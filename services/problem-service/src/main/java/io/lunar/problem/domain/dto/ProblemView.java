package io.lunar.problem.domain.dto;

/** 题目视图：创建接口的返回，只含标识与当前版本指针。 */
public record ProblemView(String problemId, String title, String currentVersionId) {}
