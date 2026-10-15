package io.lunar.problem.domain.dto;

/** 测试点视图：仅老师/管理员，或后续判题链路的服务间接口可见。 */
public record TestcaseView(String name, String inputContent, String expectedOutput) {}
