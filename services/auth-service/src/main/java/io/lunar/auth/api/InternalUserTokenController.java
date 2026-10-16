package io.lunar.auth.api;

import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.web.RequestIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 令牌失效时间线的服务间查询（后续后端优化计划 BE-09 第二部分）。
 *
 * 与 JWKS 同一信任模型：只在集群内部可达（网关不路由 `/api/v1/internal/**`），不需要令牌。
 * 资源服务在本地缓存这个值（默认 30 秒），据此拒绝"早于最近一次失效点"签发的令牌；
 * 缓存 TTL 就是失效传播的承诺时限。
 */
@RestController
@RequestMapping("/api/v1/internal/users")
public class InternalUserTokenController {

    private final UserMapper userMapper;

    public InternalUserTokenController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/{userId}/tokens-valid-after")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tokensValidAfter(
            @PathVariable String userId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        UserEntity user = userMapper.selectById(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        // 用户不存在时返回空串：调用方按"没有失效点"处理（是否该拒绝由调用方的策略决定）
        body.put("tokensValidAfter",
                user == null || user.getTokensValidAfter() == null ? "" : user.getTokensValidAfter().toString());
        return ResponseEntity.ok(ApiResponse.success(requestId, body));
    }
}
