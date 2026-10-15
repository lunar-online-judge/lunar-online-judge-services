package io.lunar.anticheat.domain.dto;

import java.util.List;

/** 分页列表响应：items 为当前页风险事件。 */
public record ReviewPage(List<RiskEventView> items, long total, long page, long pageSize) {}
