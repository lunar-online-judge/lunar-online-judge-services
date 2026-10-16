package io.lunar.audit.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 审计写入请求体（服务间）：actorId / actorRole 由服务端从令牌推导，不由请求体传入。
 *
 * 为什么在这里做格式校验：账本是**追加式**的（DB 触发器拒绝 UPDATE/DELETE），写错就永久留在里面，
 * 所以格式必须在入口拦住。action 保持**自由字符串**（新增动作不该因为白名单没更新而丢账，
 * 这是文档写明的约定），但必须是大写下划线形式——否则任意文本（含换行）会被写进结构化字段与日志。
 */
public record RecordRequest(@NotBlank
                            @Size(max = 64)
                            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$",
                                    message = "action 必须是大写下划线形式，如 STUDENT_CREATED")
                            String action,
                            @Size(max = 32) String targetType,
                            @Pattern(regexp = "^$|^[0-9a-fA-F-]{36}$", message = "targetId 必须是 uuid")
                            String targetId,
                            @Pattern(regexp = "^$|^[0-9a-fA-F-]{36}$", message = "contestId 必须是 uuid")
                            String contestId,
                            @Size(max = 2000) String detail) {}
