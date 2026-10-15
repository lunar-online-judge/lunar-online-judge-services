package io.lunar.audit.domain.dto;

import java.util.List;

/** 分页列表响应：items 为当前页审计记录。 */
public record AuditPage(List<AuditRecordView> items, long total, long page, long pageSize) {}
