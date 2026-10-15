package io.lunar.problem.domain.dto;

/** 创建/复制版本时传入的测试点；判题与管理链路可见，考生视角不下发。 */
public record TestcasePayload(String name, String inputContent, String expectedOutput) {}
