package io.lunar.auth.domain.dto;

import java.time.Instant;

/** 签发结果：expiresAt 与令牌的 exp 声明一致（时间声明秒级截断）。 */
public record IssuedToken(String accessToken, Instant expiresAt) {}
