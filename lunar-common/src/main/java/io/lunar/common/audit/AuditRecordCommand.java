package io.lunar.common.audit;

/**
 * 审计写入请求体（服务间）：字段与 audit-service 的 RecordRequest 一致。
 * actorId / actorRole 由 audit-service 从令牌推导，不由请求体传入。
 *
 * 可选字段请传 {@code null}（语义是"没有"）——**不要传空串**：`contestId` 在账本里是 uuid 列，
 * 空串会被 PostgreSQL 直接拒绝（实测踩过：{@code invalid input syntax for type uuid: ""}）。
 */
public record AuditRecordCommand(String action, String targetType, String targetId,
                                 String contestId, String detail) {
}
