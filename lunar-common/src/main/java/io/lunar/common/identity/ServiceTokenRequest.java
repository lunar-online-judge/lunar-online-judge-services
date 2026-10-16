package io.lunar.common.identity;

/**
 * 服务令牌申请体（客户端凭证）：clientId 即服务名（如 contest-service），
 * clientSecret 由部署环境注入，两端必须一致。
 */
public record ServiceTokenRequest(String clientId, String clientSecret) {
}
